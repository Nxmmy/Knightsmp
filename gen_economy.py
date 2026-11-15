"""Generates shop.yml, blocks.yml, enchants.yml, potions.yml, kits.yml and market.yml for ModsForKnightSMP.
All prices are Dollars. EXACT prices from the server owner's specification are marked EXACT and must never be rescaled.
Run:  python3 gen_economy.py <resources-dir>
"""
import sys, re, json, math, os
OUT = sys.argv[1] if len(sys.argv) > 1 else "."
W = "OAK|SPRUCE|BIRCH|JUNGLE|ACACIA|DARK_OAK|MANGROVE|CHERRY|PALE_OAK|BAMBOO|CRIMSON|WARPED"
WO = "OAK|SPRUCE|BIRCH|JUNGLE|ACACIA|DARK_OAK|MANGROVE|CHERRY|PALE_OAK"
C = "WHITE|ORANGE|MAGENTA|LIGHT_BLUE|YELLOW|LIME|PINK|GRAY|LIGHT_GRAY|CYAN|PURPLE|BLUE|BROWN|GREEN|RED|BLACK"
CP = "(" + C + ")"
CO = "(WAXED_)?(EXPOSED_|WEATHERED_|OXIDIZED_)?"

EXCLUDE = [
    r"^(AIR|CAVE_AIR|VOID_AIR|BEDROCK|BARRIER|LIGHT|STRUCTURE_BLOCK|STRUCTURE_VOID|JIGSAW|DEBUG_STICK|KNOWLEDGE_BOOK)$",
    r"^.*COMMAND_BLOCK$",
    r"^(END_PORTAL|END_PORTAL_FRAME|END_GATEWAY|NETHER_PORTAL|SPAWNER|TRIAL_SPAWNER|VAULT|REINFORCED_DEEPSLATE|BUDDING_AMETHYST|FARMLAND|PETRIFIED_OAK_SLAB|CHORUS_PLANT|FROGSPAWN|FROSTED_ICE|FIRE|SOUL_FIRE|TEST_BLOCK|TEST_INSTANCE_BLOCK)$",
    r"^INFESTED_.*$",
    r"^SUSPICIOUS_(SAND|GRAVEL)$",
    r"^LEGACY_.*$",
]
# (regex, category, unit $/item, pack amount, applies-to-non-block-items)
R = []
def rule(rx, cat, unit, amt=16, item=False): R.append((rx, cat, unit, amt, item))

# ---- free armor trims (EXACT: Armor Trims are free)
rule(r"^.*_ARMOR_TRIM_SMITHING_TEMPLATE$", "combat", 0, 1, True)
# ---- redstone: every price is DERIVED from the cost of its ingredients (unit values below are the shop's own prices) plus a
# convenience markup that grows with the number of crafting steps:  1 step x1.25, 2 steps x1.35 (3+ x1.45). Not invented.
U = dict(redstone=35/16, iron=25/8, gold=20/8, quartz=30/16, cobble=0.2, stone=0.3, plank=0.15, stick=0.075, string=14/16, slime=40/8,
         glowdust=32/16, copper=15/16, wheat=0.5, glass=0.5, blaze_rod=60/4, amethyst=28/8, honey=16/4, wood_slab=0.1)
def mk(steps): return {1: 1.25, 2: 1.35}.get(steps, 1.45)
def derive(cost, steps, made=1): return cost * mk(steps) / made
torch = U["redstone"] + U["stick"]                                   # 1 redstone + 1 stick
chest = 8 * U["plank"]; hay = 9 * U["wheat"]; bow = 3 * U["stick"] + 3 * U["string"]; glowstone = 4 * U["glowdust"]
dropper = 7 * U["cobble"] + U["redstone"]
REDSTONE_DERIVATION = {   # name: (unit price, pack size, recipe note)
 "REDSTONE_TORCH": (derive(torch, 1), 4, "redstone + stick"),
 "LEVER": (derive(U["stick"] + U["cobble"], 1), 8, "stick + cobblestone"),
 "REDSTONE_BLOCK": (derive(9 * U["redstone"], 1), 1, "9 redstone"),
 "REPEATER": (derive(2 * torch + U["redstone"] + 3 * U["stone"], 2), 2, "2 torches + redstone + 3 stone"),
 "COMPARATOR": (derive(3 * torch + U["quartz"] + 3 * U["stone"], 2), 1, "3 torches + quartz + 3 stone"),
 "OBSERVER": (derive(6 * U["cobble"] + 2 * U["redstone"] + U["quartz"], 1), 2, "6 cobble + 2 redstone + quartz"),
 "PISTON": (derive(3 * U["plank"] + 4 * U["cobble"] + U["iron"] + U["redstone"], 1), 2, "3 planks + 4 cobble + iron + redstone"),
 "STICKY_PISTON": (derive(3 * U["plank"] + 4 * U["cobble"] + U["iron"] + U["redstone"] + U["slime"], 2), 2, "piston + slime ball"),
 "DROPPER": (derive(dropper, 1), 2, "7 cobble + redstone"),
 "DISPENSER": (derive(7 * U["cobble"] + bow + U["redstone"], 2), 2, "7 cobble + bow + redstone"),
 "HOPPER": (derive(5 * U["iron"] + chest, 2), 1, "5 iron + chest"),
 "TARGET": (derive(4 * U["redstone"] + hay, 2), 1, "4 redstone + hay block"),
 "TRIPWIRE_HOOK": (derive((U["iron"] + U["stick"] + U["plank"]) / 2, 1), 2, "iron + stick + plank (makes 2)"),
 "DAYLIGHT_DETECTOR": (derive(3 * U["glass"] + 3 * U["quartz"] + 3 * U["wood_slab"], 2), 1, "3 glass + 3 quartz + 3 wood slabs"),
 "REDSTONE_LAMP": (derive(4 * U["redstone"] + glowstone, 1), 2, "4 redstone + glowstone"),
 "NOTE_BLOCK": (derive(8 * U["plank"] + U["redstone"], 1), 2, "8 planks + redstone"),
 "CRAFTER": (derive(5 * U["iron"] + 2 * U["redstone"] + (4 * U["plank"] + 0.0) + dropper, 3), 1, "5 iron + 2 redstone + crafting table + dropper"),
 "TRAPPED_CHEST": (derive(chest + (U["iron"] + U["stick"] + U["plank"]) / 2, 2), 2, "chest + tripwire hook"),
 "RAIL": (derive((6 * U["iron"] + U["stick"]) / 16, 1), 16, "6 iron + stick (makes 16)"),
 "POWERED_RAIL": (derive((6 * U["gold"] + U["stick"] + U["redstone"]) / 6, 1), 8, "6 gold + stick + redstone (makes 6)"),
 "DETECTOR_RAIL": (derive((6 * U["iron"] + U["stone"] * 2 + U["redstone"]) / 6, 2), 8, "6 iron + stone plate + redstone (makes 6)"),
 "ACTIVATOR_RAIL": (derive((6 * U["iron"] + 2 * U["stick"] + torch) / 6, 2), 8, "6 iron + 2 sticks + torch (makes 6)"),
 "SLIME_BLOCK": (derive(9 * U["slime"], 1), 1, "9 slime balls"),
 "HONEY_BLOCK": (derive(4 * U["honey"], 1), 1, "4 honey bottles"),
 "LIGHTNING_ROD": (derive(3 * U["copper"], 1), 1, "3 copper ingots"),
 "SCULK_SENSOR": (40, 1, "silk-touch only: rarity value"),
 "CALIBRATED_SCULK_SENSOR": (derive(40 + 3 * U["amethyst"], 1), 1, "sculk sensor + 3 amethyst shards"),
}
def rsrule(name, rx=None):
    unit, amt, _ = REDSTONE_DERIVATION[name]
    rule(rx or "^" + name + "$", "redstone", round(unit, 3), amt)
rule(r"^HEAVY_WEIGHTED_PRESSURE_PLATE$", "redstone", round(derive(2 * U["iron"], 1), 3), 1)      # 2 iron
rule(r"^LIGHT_WEIGHTED_PRESSURE_PLATE$", "redstone", round(derive(2 * U["gold"], 1), 3), 1)      # 2 gold
rule(r"^(STONE|POLISHED_BLACKSTONE)_PRESSURE_PLATE$", "redstone", round(derive(2 * U["stone"], 1), 3), 4)
rule(r"^.*_PRESSURE_PLATE$", "redstone", round(derive(2 * U["plank"], 1), 3), 4)                  # wooden: 2 planks
rule(r"^(STONE|POLISHED_BLACKSTONE)_BUTTON$", "redstone", round(derive(U["stone"], 1), 3), 8)
rule(r"^.*_BUTTON$", "redstone", round(derive(U["plank"], 1), 3), 8)                              # wooden: 1 plank
for n in REDSTONE_DERIVATION:
    if n in ("LIGHTNING_ROD",): rsrule(n, r"^(WAXED_)?(EXPOSED_|WEATHERED_|OXIDIZED_)?LIGHTNING_ROD$")
    elif n in ("RAIL",): rsrule(n)
    else: rsrule(n)
rule(r"^" + CO + r"COPPER_BULB$", "redstone", round(derive((3 * 9 * U["copper"] + U["blaze_rod"] + U["redstone"]) / 4, 2), 3), 2)
# ---- ore blocks (silk touch) appended to the Ores category
for nm, price in (("COAL", 4), ("COPPER", 4), ("IRON", 12), ("GOLD", 18), ("REDSTONE", 14), ("LAPIS", 12), ("DIAMOND", 110), ("EMERALD", 40)):
    rule(r"^(DEEPSLATE_)?" + nm + r"_ORE$", "ores", price, 1)
rule(r"^NETHER_GOLD_ORE$", "ores", 12, 1); rule(r"^NETHER_QUARTZ_ORE$", "ores", 10, 1)
# ---- metal & gem blocks
for nm, price in (("NETHERITE_BLOCK", 12000), ("DIAMOND_BLOCK", 135), ("EMERALD_BLOCK", 44), ("GOLD_BLOCK", 29), ("IRON_BLOCK", 36),
                  ("LAPIS_BLOCK", 18), ("COAL_BLOCK", 11), ("RAW_IRON_BLOCK", 32), ("RAW_GOLD_BLOCK", 26), ("RAW_COPPER_BLOCK", 9)):
    rule(r"^" + nm + r"$", "metal", price, 1)
rule(r"^" + CO + r"(COPPER_BLOCK|COPPER|CUT_COPPER|CUT_COPPER_STAIRS|CUT_COPPER_SLAB|CHISELED_COPPER|COPPER_GRATE)$", "metal", 8, 4)
rule(r"^" + CO + r"COPPER_(DOOR|TRAPDOOR)$", "metal", 10, 2)
rule(r"^" + CO + r"COPPER_(CHEST|GOLEM_STATUE|LANTERN|TORCH|CHAIN|BARS)$", "utility", 10, 2)
rule(r"^(AMETHYST_BLOCK|SMALL_AMETHYST_BUD|MEDIUM_AMETHYST_BUD|LARGE_AMETHYST_BUD|AMETHYST_CLUSTER)$", "nature_terrain", 12, 4)
# ---- special rare blocks
rule(r"^DRAGON_EGG$", "utility", 4000, 1); rule(r"^BEACON$", "utility", 2500, 1); rule(r"^CONDUIT$", "utility", 900, 1)
rule(r"^HEAVY_CORE$", "utility", 1500, 1); rule(r"^WITHER_SKELETON_SKULL$", "utility", 350, 1); rule(r"^DRAGON_HEAD$", "utility", 900, 1)
rule(r"^(CREEPER_HEAD|ZOMBIE_HEAD|PLAYER_HEAD|PIGLIN_HEAD|SKELETON_SKULL)$", "utility", 120, 1)
rule(r"^ENCHANTING_TABLE$", "utility", 160, 1); rule(r"^ENDER_CHEST$", "utility", 90, 1); rule(r"^LODESTONE$", "utility", 260, 1)
rule(r"^RESPAWN_ANCHOR$", "utility", 220, 1); rule(r"^(ANVIL)$", "utility", 90, 1); rule(r"^CHIPPED_ANVIL$", "utility", 60, 1); rule(r"^DAMAGED_ANVIL$", "utility", 40, 1)
rule(r"^(.*)_FROGLIGHT$", "utility", 40, 1); rule(r"^SEA_LANTERN$", "utility", 14, 2)
# ---- wood logs / nature wood
rule(r"^STRIPPED_(" + WO + r")_(LOG|WOOD)$", "nature_wood", 0.7, 16)
rule(r"^(" + WO + r")_(LOG|WOOD)$", "nature_wood", 0.6, 16)
rule(r"^(STRIPPED_)?(CRIMSON|WARPED)_(STEM|HYPHAE)$", "nature_wood", 1.1, 16)
rule(r"^(STRIPPED_)?BAMBOO_BLOCK$", "nature_wood", 0.8, 16)
rule(r"^(MANGROVE_ROOTS|MUDDY_MANGROVE_ROOTS)$", "nature_wood", 1.0, 8)
# ---- wood blocks
rule(r"^(" + W + r")_PLANKS$", "woodblocks", 0.15, 32); rule(r"^BAMBOO_MOSAIC$", "woodblocks", 0.25, 16)
rule(r"^(" + W + r")(_MOSAIC)?_STAIRS$", "woodblocks", 0.2, 16); rule(r"^(" + W + r")(_MOSAIC)?_SLAB$", "woodblocks", 0.1, 32)
rule(r"^(" + W + r")_FENCE$", "woodblocks", 0.25, 16); rule(r"^(" + W + r")_FENCE_GATE$", "woodblocks", 0.35, 8)
rule(r"^(" + W + r")_DOOR$", "woodblocks", 0.35, 4); rule(r"^(" + W + r")_TRAPDOOR$", "woodblocks", 0.3, 4)
rule(r"^(" + W + r")_SIGN$", "woodblocks", 0.3, 4); rule(r"^(" + W + r")_HANGING_SIGN$", "woodblocks", 0.8, 4)
rule(r"^(" + W + r")_SHELF$", "woodblocks", 0.6, 4)
# ---- leaves / saplings
rule(r"^(" + WO + r"|AZALEA|FLOWERING_AZALEA)_LEAVES$", "nature_leaves", 0.3, 32)
rule(r"^.*_SAPLING$", "nature_leaves", 1.2, 4); rule(r"^MANGROVE_PROPAGULE$", "nature_leaves", 1.5, 4)
rule(r"^(AZALEA|FLOWERING_AZALEA)$", "nature_leaves", 4, 2)
# ---- flowers & plants
rule(r"^(DANDELION|POPPY|BLUE_ORCHID|ALLIUM|AZURE_BLUET|RED_TULIP|ORANGE_TULIP|WHITE_TULIP|PINK_TULIP|OXEYE_DAISY|CORNFLOWER|LILY_OF_THE_VALLEY|TORCHFLOWER|PINK_PETALS|WILDFLOWERS|CACTUS_FLOWER|OPEN_EYEBLOSSOM|CLOSED_EYEBLOSSOM)$", "nature_plants", 0.6, 8)
rule(r"^WITHER_ROSE$", "nature_plants", 100, 1)
rule(r"^(SUNFLOWER|LILAC|ROSE_BUSH|PEONY|PITCHER_PLANT)$", "nature_plants", 0.9, 8)
rule(r"^SPORE_BLOSSOM$", "nature_plants", 12, 2)
rule(r"^(SHORT_GRASS|TALL_GRASS|FERN|LARGE_FERN|DEAD_BUSH|BUSH|FIREFLY_BUSH|LEAF_LITTER)$", "nature_plants", 0.3, 16)
rule(r"^(MOSS_BLOCK|MOSS_CARPET|PALE_MOSS_BLOCK|PALE_MOSS_CARPET|PALE_HANGING_MOSS)$", "nature_plants", 1.2, 8)
rule(r"^SEA_PICKLE$", "nature_plants", 1.5, 4)
rule(r"^(SEAGRASS|KELP|VINE|LILY_PAD|GLOW_LICHEN|WEEPING_VINES|TWISTING_VINES|HANGING_ROOTS|NETHER_SPROUTS|CRIMSON_ROOTS|WARPED_ROOTS|BIG_DRIPLEAF|SMALL_DRIPLEAF|SUGAR_CANE|BAMBOO|CACTUS)$", "nature_plants", 0.4, 16, True)
# ---- crops & mushrooms
rule(r"^(WHEAT_SEEDS|PUMPKIN_SEEDS|MELON_SEEDS|BEETROOT_SEEDS|TORCHFLOWER_SEEDS|PITCHER_POD)$", "nature_crops", 0.25, 16, True)
rule(r"^(WHEAT|CARROT|POTATO|BEETROOT|MELON_SLICE|SWEET_BERRIES|GLOW_BERRIES|COCOA_BEANS|CHORUS_FRUIT|POISONOUS_POTATO)$", "nature_crops", 0.5, 16, True)
rule(r"^(NETHER_WART|CRIMSON_FUNGUS|WARPED_FUNGUS|RED_MUSHROOM|BROWN_MUSHROOM)$", "nature_crops", 0.7, 8, True)
rule(r"^PUMPKIN$", "nature_crops", 1, 8); rule(r"^MELON$", "nature_crops", 1.5, 4); rule(r"^HAY_BLOCK$", "nature_crops", 4.5, 2)
rule(r"^DRIED_KELP_BLOCK$", "nature_crops", 2, 4); rule(r"^DRIED_KELP$", "nature_crops", 0.3, 16, True)
rule(r"^(RED_MUSHROOM_BLOCK|BROWN_MUSHROOM_BLOCK|MUSHROOM_STEM)$", "nature_crops", 2, 4)
# ---- terrain & ocean
rule(r"^DIRT$", "nature_terrain", 0.08, 64); rule(r"^COARSE_DIRT$", "nature_terrain", 0.1, 64); rule(r"^ROOTED_DIRT$", "nature_terrain", 1, 8)
rule(r"^PODZOL$", "nature_terrain", 0.6, 16); rule(r"^MYCELIUM$", "nature_terrain", 1.5, 8); rule(r"^GRASS_BLOCK$", "nature_terrain", 0.25, 16)
rule(r"^DIRT_PATH$", "nature_terrain", 0.4, 16); rule(r"^MUD$", "nature_terrain", 0.2, 32); rule(r"^PACKED_MUD$", "nature_terrain", 0.3, 32)
rule(r"^CLAY$", "nature_terrain", 0.4, 16); rule(r"^SAND$", "nature_terrain", 0.12, 64); rule(r"^RED_SAND$", "nature_terrain", 0.15, 64)
rule(r"^GRAVEL$", "nature_terrain", 0.12, 64); rule(r"^SNOW_BLOCK$", "nature_terrain", 0.3, 16); rule(r"^SNOW$", "nature_terrain", 0.1, 16)
rule(r"^ICE$", "nature_terrain", 1.2, 8); rule(r"^PACKED_ICE$", "nature_terrain", 3, 8); rule(r"^BLUE_ICE$", "nature_terrain", 10, 4)
rule(r"^(SPONGE|WET_SPONGE)$", "nature_terrain", 12, 2); rule(r"^OBSIDIAN$", "nature_terrain", 4, 4); rule(r"^CRYING_OBSIDIAN$", "nature_terrain", 12, 2)
rule(r"^COBWEB$", "nature_terrain", 3, 4)
rule(r"^(DEAD_)?(TUBE|BRAIN|BUBBLE|FIRE|HORN)_CORAL(_BLOCK|_FAN)?$", "nature_terrain", 1.5, 4)
rule(r"^(SCULK|SCULK_VEIN)$", "nature_terrain", 6, 4); rule(r"^SCULK_CATALYST$", "nature_terrain", 60, 1); rule(r"^SCULK_SHRIEKER$", "nature_terrain", 90, 1)
rule(r"^TURTLE_EGG$", "nature_terrain", 40, 1); rule(r"^SNIFFER_EGG$", "nature_terrain", 300, 1)
rule(r"^(DARK_)?PRISMARINE(_BRICKS)?(_STAIRS|_SLAB|_WALL)?$", "nature_terrain", 8, 4)
rule(r"^(BEE_NEST)$", "utility", 70, 1); rule(r"^(BEEHIVE)$", "utility", 18, 1); rule(r"^HONEYCOMB_BLOCK$", "utility", 8, 2)
# ---- colored blocks
rule(r"^" + CP + r"_WOOL$", "colored", 1.2, 16); rule(r"^" + CP + r"_CARPET$", "colored", 0.4, 16)
rule(r"^" + CP + r"_CONCRETE$", "colored", 0.6, 32); rule(r"^" + CP + r"_CONCRETE_POWDER$", "colored", 0.5, 32)
rule(r"^(" + CP + r"_)?TERRACOTTA$", "colored", 0.5, 16); rule(r"^" + CP + r"_GLAZED_TERRACOTTA$", "colored", 1.4, 8)
rule(r"^" + CP + r"_STAINED_GLASS$", "colored", 0.6, 16); rule(r"^" + CP + r"_STAINED_GLASS_PANE$", "colored", 0.25, 16)
rule(r"^GLASS$", "colored", 0.5, 16); rule(r"^GLASS_PANE$", "colored", 0.2, 16); rule(r"^TINTED_GLASS$", "colored", 4, 4)
rule(r"^" + CP + r"_BANNER$", "colored", 2, 1); rule(r"^" + CP + r"_BED$", "colored", 6, 1); rule(r"^(" + CP + r"_)?CANDLE$", "colored", 1.2, 4)
rule(r"^(" + CP + r"_)?SHULKER_BOX$", "colored", 220, 1)
# ---- nether & end
rule(r"^NETHERRACK$", "nether_end", 0.1, 64)
rule(r"^(NETHER_BRICK|RED_NETHER_BRICK|CRACKED_NETHER_BRICK|CHISELED_NETHER_BRICK)S?(_STAIRS|_SLAB|_WALL|_FENCE)?$", "nether_end", 0.5, 32)
rule(r"^GILDED_BLACKSTONE$", "nether_end", 8, 4)
rule(r"^(POLISHED_)?(BLACKSTONE|BASALT)(_STAIRS|_SLAB|_WALL)?$|^SMOOTH_BASALT$|^CHISELED_POLISHED_BLACKSTONE$|^(CRACKED_)?POLISHED_BLACKSTONE_BRICKS?(_STAIRS|_SLAB|_WALL)?$", "nether_end", 0.35, 32)
rule(r"^(SOUL_SAND|SOUL_SOIL)$", "nether_end", 0.6, 16); rule(r"^GLOWSTONE$", "nether_end", 3, 8); rule(r"^MAGMA_BLOCK$", "nether_end", 2, 8)
rule(r"^(NETHER_WART_BLOCK|WARPED_WART_BLOCK)$", "nether_end", 1.5, 8); rule(r"^SHROOMLIGHT$", "nether_end", 3, 4); rule(r"^BONE_BLOCK$", "nether_end", 2.5, 4)
rule(r"^END_STONE$", "nether_end", 0.6, 32); rule(r"^END_STONE_BRICK(S|_STAIRS|_SLAB|_WALL)$", "nether_end", 0.8, 32)
rule(r"^PURPUR(_BLOCK|_PILLAR|_STAIRS|_SLAB)$", "nether_end", 1.2, 16); rule(r"^CHORUS_FLOWER$", "nether_end", 6, 2)
rule(r"^(QUARTZ_BLOCK|QUARTZ_BRICKS|QUARTZ_PILLAR|CHISELED_QUARTZ_BLOCK|SMOOTH_QUARTZ|QUARTZ_SLAB|QUARTZ_STAIRS|SMOOTH_QUARTZ_SLAB|SMOOTH_QUARTZ_STAIRS)$", "nether_end", 6, 8)
# ---- stone & bricks
rule(r"^(MOSSY_)?(COBBLESTONE|STONE)(_STAIRS|_SLAB|_WALL)?$|^(ANDESITE|DIORITE|GRANITE|TUFF|CALCITE|DRIPSTONE_BLOCK)(_STAIRS|_SLAB|_WALL)?$", "stone", 0.2, 64)
rule(r"^(COBBLED_)?DEEPSLATE(_STAIRS|_SLAB|_WALL)?$|^COBBLED_DEEPSLATE(_STAIRS|_SLAB|_WALL)?$", "stone", 0.25, 64)
rule(r"^POLISHED_(ANDESITE|DIORITE|GRANITE|TUFF|DEEPSLATE)(_STAIRS|_SLAB|_WALL)?$|^SMOOTH_STONE(_SLAB)?$", "stone", 0.3, 32)
rule(r"^(BRICKS|BRICK_STAIRS|BRICK_SLAB|BRICK_WALL)$", "stone", 0.6, 16)
rule(r"^(MOSSY_|CRACKED_|CHISELED_)?(STONE_BRICK|DEEPSLATE_BRICK|DEEPSLATE_TILE|TUFF_BRICK|MUD_BRICK)S?(_STAIRS|_SLAB|_WALL)?$|^CHISELED_(DEEPSLATE|TUFF|STONE_BRICKS)$|^CRACKED_DEEPSLATE_(BRICKS|TILES)$", "stone", 0.5, 32)
rule(r"^(CUT_|CHISELED_|SMOOTH_)?(RED_)?SANDSTONE(_STAIRS|_SLAB|_WALL)?$", "stone", 0.5, 32)
# ---- utility & decoration
for nm, price, amt in (("CRAFTING_TABLE", 2, 1), ("CHEST", 4, 2), ("BARREL", 3, 2), ("BOOKSHELF", 12, 1), ("CHISELED_BOOKSHELF", 14, 1), ("LECTERN", 10, 1),
                       ("COMPOSTER", 3, 1), ("LOOM", 6, 1), ("CARTOGRAPHY_TABLE", 6, 1), ("FLETCHING_TABLE", 6, 1), ("SMITHING_TABLE", 12, 1),
                       ("GRINDSTONE", 10, 1), ("STONECUTTER", 12, 1), ("FURNACE", 2, 1), ("BLAST_FURNACE", 25, 1), ("SMOKER", 8, 1),
                       ("CAMPFIRE", 4, 1), ("SOUL_CAMPFIRE", 5, 1), ("CAULDRON", 25, 1), ("BREWING_STAND", 12, 1), ("BELL", 25, 1),
                       ("JUKEBOX", 25, 1), ("LANTERN", 8, 2), ("SOUL_LANTERN", 9, 2), ("END_ROD", 6, 2), ("FLOWER_POT", 2, 2),
                       ("DECORATED_POT", 8, 1), ("JACK_O_LANTERN", 4, 2), ("CARVED_PUMPKIN", 3, 2), ("IRON_BARS", 3, 8), ("SCAFFOLDING", 1.5, 8)):
    rule(r"^" + nm + r"$", "utility", price, amt)
rule(r"^LADDER$", "utility", 0.3, 16); rule(r"^TORCH$", "utility", 0.3, 16); rule(r"^SOUL_TORCH$", "utility", 0.5, 16)
rule(r"^(IRON_)?CHAIN$", "utility", 4, 4); rule(r"^IRON_(DOOR|TRAPDOOR)$", "utility", 12, 1)
FALLBACK = ("utility", 1.5, 8)

def apply_rules(name):
    for rx, cat, unit, amt, item in R:
        if re.fullmatch(rx.replace("^", "").replace("$", "") if False else rx, name): return (cat, unit, amt, item)
    return None
def excluded(name): return any(re.fullmatch(x, name) for x in EXCLUDE)

# ===================== explicit shop tables =====================
# EXACT (owner specification): coal, raw iron, iron ingot, gold, copper ingot, lapis, redstone, diamonds, emerald, debris, scrap, tnt
ORES = [("COAL", 16, 15), ("RAW_IRON", 8, 25), ("IRON_INGOT", 8, 25), ("GOLD_INGOT", 8, 20), ("COPPER_INGOT", 16, 15), ("LAPIS_LAZULI", 32, 50),
        ("REDSTONE", 16, 35), ("DIAMOND", 16, 185), ("EMERALD", 4, 15), ("ANCIENT_DEBRIS", 2, 750), ("NETHERITE_SCRAP", 2, 500), ("TNT", 32, 75),
        # extras (not in the specification): priced from the exact rows above
        ("RAW_COPPER", 8, 12), ("RAW_GOLD", 8, 24), ("QUARTZ", 16, 30), ("AMETHYST_SHARD", 8, 28), ("GLOWSTONE_DUST", 16, 32), ("NETHERITE_INGOT", 1, 1200),
        ("NETHERITE_UPGRADE_SMITHING_TEMPLATE", 1, 350)]
SETS = [("Full Iron Armor Set", "IRON", 150), ("Full Diamond Armor Set", "DIAMOND", 250), ("Full Netherite Armor Set", "NETHERITE", 3500)]   # EXACT
EXTRA_SETS = [("Full Leather Armor Set", "LEATHER", 25, False), ("Full Chainmail Armor Set", "CHAINMAIL", 80, False), ("Full Golden Armor Set", "GOLDEN", 70, False), ("Full Copper Armor Set", "COPPER", 55, True)]
COMBAT = [  # (material, amount, price, optional)   swords are EXACT: iron 35, diamond 450, netherite 1400
    ("WOODEN_SWORD", 1, 6), ("STONE_SWORD", 1, 12), ("GOLDEN_SWORD", 1, 25), ("IRON_SWORD", 1, 35), ("DIAMOND_SWORD", 1, 450), ("NETHERITE_SWORD", 1, 1400),
    ("IRON_HELMET", 1, 38), ("IRON_CHESTPLATE", 1, 60), ("IRON_LEGGINGS", 1, 55), ("IRON_BOOTS", 1, 30),
    ("DIAMOND_HELMET", 1, 70), ("DIAMOND_CHESTPLATE", 1, 110), ("DIAMOND_LEGGINGS", 1, 100), ("DIAMOND_BOOTS", 1, 60),
    ("NETHERITE_HELMET", 1, 900), ("NETHERITE_CHESTPLATE", 1, 1300), ("NETHERITE_LEGGINGS", 1, 1150), ("NETHERITE_BOOTS", 1, 800),
    ("TURTLE_HELMET", 1, 150), ("BOW", 1, 35), ("CROSSBOW", 1, 55), ("ARROW", 32, 24), ("SHIELD", 1, 40), ("TRIDENT", 1, 350), ("MACE", 1, 2500),
    ("TOTEM_OF_UNDYING", 1, 900), ("WIND_CHARGE", 16, 40), ("SPECTRAL_ARROW", 16, 45), ("WOLF_ARMOR", 1, 65),
    ("LEATHER_HORSE_ARMOR", 1, 20), ("IRON_HORSE_ARMOR", 1, 40), ("GOLDEN_HORSE_ARMOR", 1, 60), ("DIAMOND_HORSE_ARMOR", 1, 120),
    ("COPPER_SWORD", 1, 20), ("COPPER_SPEAR", 1, 22),
    ("WOODEN_SPEAR", 1, 8), ("STONE_SPEAR", 1, 15), ("IRON_SPEAR", 1, 40), ("GOLDEN_SPEAR", 1, 30), ("DIAMOND_SPEAR", 1, 130), ("NETHERITE_SPEAR", 1, 1300)]
OPTIONAL = {"COPPER_SWORD", "COPPER_SPEAR", "SPECTRAL_ARROW", "WOLF_ARMOR", "WOODEN_SPEAR", "STONE_SPEAR", "IRON_SPEAR", "GOLDEN_SPEAR", "DIAMOND_SPEAR", "NETHERITE_SPEAR", "WIND_CHARGE", "MACE"}
TOOLS = []
for tier, pk, ax, sh, ho in (("WOODEN", 5, 5, 4, 4), ("STONE", 10, 10, 8, 8), ("GOLDEN", 20, 20, 15, 15), ("IRON", 40, 40, 22, 30), ("DIAMOND", 120, 120, 65, 90), ("NETHERITE", 1350, 1400, 1100, 1000)):
    TOOLS += [(tier + "_PICKAXE", 1, pk), (tier + "_AXE", 1, ax), (tier + "_SHOVEL", 1, sh), (tier + "_HOE", 1, ho)]
TOOLS += [("SHEARS", 1, 25), ("FISHING_ROD", 1, 15), ("FLINT_AND_STEEL", 1, 18), ("BUCKET", 1, 30), ("WATER_BUCKET", 1, 34), ("LAVA_BUCKET", 1, 50),
          ("COMPASS", 1, 28), ("CLOCK", 1, 35), ("SPYGLASS", 1, 30), ("BRUSH", 1, 22), ("CARROT_ON_A_STICK", 1, 20), ("WARPED_FUNGUS_ON_A_STICK", 1, 22),
          ("LEAD", 2, 16), ("NAME_TAG", 1, 50), ("SADDLE", 1, 90), ("ELYTRA", 1, 2500), ("MAP", 1, 12), ("ENDER_EYE", 4, 90), ("FIREWORK_ROCKET", 16, 30)]
FOOD = [("BREAD", 16, 18), ("COOKED_BEEF", 16, 30), ("COOKED_PORKCHOP", 16, 30), ("COOKED_CHICKEN", 16, 22), ("COOKED_MUTTON", 16, 26), ("COOKED_RABBIT", 16, 24),
        ("COOKED_COD", 16, 18), ("COOKED_SALMON", 16, 22), ("BAKED_POTATO", 16, 16), ("PUMPKIN_PIE", 8, 28), ("GOLDEN_CARROT", 8, 45), ("APPLE", 8, 16),
        ("GOLDEN_APPLE", 1, 110), ("ENCHANTED_GOLDEN_APPLE", 1, 2500), ("CAKE", 1, 24), ("COOKIE", 16, 12), ("MUSHROOM_STEW", 1, 8), ("BEETROOT_SOUP", 1, 8),
        ("RABBIT_STEW", 1, 14), ("HONEY_BOTTLE", 4, 16), ("MILK_BUCKET", 1, 26), ("BEEF", 16, 14), ("PORKCHOP", 16, 14), ("CHICKEN", 16, 10), ("MUTTON", 16, 12),
        ("RABBIT", 16, 12), ("COD", 16, 10), ("SALMON", 16, 12), ("TROPICAL_FISH", 4, 10), ("PUFFERFISH", 4, 16)]
MISC = [("STICK", 16, 6), ("STRING", 16, 14), ("LEATHER", 8, 24), ("FEATHER", 16, 10), ("BONE", 16, 14), ("BONE_MEAL", 16, 12), ("GUNPOWDER", 16, 40),
        ("SLIME_BALL", 8, 40), ("ENDER_PEARL", 4, 60), ("BLAZE_ROD", 4, 60), ("BLAZE_POWDER", 8, 40), ("GHAST_TEAR", 1, 120), ("PHANTOM_MEMBRANE", 4, 64),
        ("SPIDER_EYE", 8, 16), ("ROTTEN_FLESH", 16, 8), ("INK_SAC", 16, 16), ("GLOW_INK_SAC", 4, 24), ("PAPER", 16, 12), ("BOOK", 4, 24), ("SUGAR", 16, 8),
        ("EGG", 16, 10), ("CLAY_BALL", 16, 14), ("BRICK", 16, 18), ("FLINT", 16, 12), ("PRISMARINE_SHARD", 8, 32), ("PRISMARINE_CRYSTALS", 8, 40),
        ("NAUTILUS_SHELL", 1, 90), ("HEART_OF_THE_SEA", 1, 800), ("ECHO_SHARD", 1, 140), ("NETHER_STAR", 1, 3000), ("SHULKER_SHELL", 2, 240),
        ("DRAGON_BREATH", 1, 400), ("EXPERIENCE_BOTTLE", 8, 64), ("ITEM_FRAME", 4, 16), ("GLOW_ITEM_FRAME", 4, 28), ("PAINTING", 4, 20),
        ("ARMOR_STAND", 1, 20), ("MINECART", 1, 24), ("GLASS_BOTTLE", 16, 14), ("SNOWBALL", 16, 6), ("FIRE_CHARGE", 8, 24), ("NETHER_WART", 8, 12)]
# (NETHER_WART is also matched by a block rule; explicit entries win)

cats = {  # id -> (name, icon, description)
    "ores": ("Ores and Mining", "DIAMOND_ORE", ["&7Ores, ingots, TNT and mining materials."]),
    "combat": ("Combat", "DIAMOND_SWORD", ["&7Armor sets, weapons and free armor trims."]),
    "tools": ("Tools", "IRON_PICKAXE", ["&7Tools and utility items."]),
    "food": ("Food", "COOKED_BEEF", ["&7Keep your hunger bar full."]),
    "redstone": ("Redstone", "REDSTONE", ["&7Every redstone component."]),
    "stone": ("Blocks: Stone & Bricks", "STONE_BRICKS", ["&7Stone, bricks, deepslate, sandstone."]),
    "woodblocks": ("Blocks: Wood", "OAK_PLANKS", ["&7Planks, stairs, slabs, doors, fences, signs."]),
    "colored": ("Blocks: Colored & Glass", "CYAN_CONCRETE", ["&7Wool, concrete, terracotta, glass, banners, beds."]),
    "metal": ("Blocks: Metal & Gems", "IRON_BLOCK", ["&7Metal, copper and gem blocks."]),
    "nether_end": ("Blocks: Nether & End", "NETHERRACK", ["&7Nether and End building blocks."]),
    "utility": ("Blocks: Utility & Decor", "CRAFTING_TABLE", ["&7Workstations, lights, heads and rare blocks."]),
    "nature_wood": ("Nature: Logs & Wood", "OAK_LOG", ["&7Every log, wood, stem and root."]),
    "nature_leaves": ("Nature: Leaves & Saplings", "OAK_SAPLING", ["&7Leaves, saplings and azaleas."]),
    "nature_plants": ("Nature: Flowers & Plants", "POPPY", ["&7Flowers, grass, vines, moss and more."]),
    "nature_crops": ("Nature: Crops & Mushrooms", "WHEAT", ["&7Seeds, crops, mushrooms and fungi."]),
    "nature_terrain": ("Nature: Terrain & Ocean", "GRASS_BLOCK", ["&7Dirt, sand, ice, coral, sculk and more."]),
    "enchantments": ("Enchantment Books", "ENCHANTED_BOOK", ["&7Every enchantment, every level.", "&7Priced by how hard it is to obtain."]),
    "potions": ("Potions", "POTION", ["&7Potions, splash and lingering variants."]),
    "misc": ("Miscellaneous", "ENDER_CHEST", ["&7Mob drops, materials and rare items."]),
}
order = ["ores", "combat", "tools", "food", "redstone", "stone", "woodblocks", "colored", "metal", "nether_end", "utility",
         "nature_wood", "nature_leaves", "nature_plants", "nature_crops", "nature_terrain", "enchantments", "potions", "misc"]
DEDUPE = {"redstone", "stone", "woodblocks", "colored", "metal", "nether_end", "utility"}   # items already in /sell are not repeated here
def fmt_item(m, a, p, opt=False):
    parts = [f"material: {m}"] + ([f"amount: {a}"] if a > 1 else []) + [f"price: {p}"] + (["optional: true"] if opt or m in OPTIONAL else [])
    return "      - {" + ", ".join(parts) + "}"
shop = ("# General shop - all prices in Dollars ($).  A price is for the WHOLE pack shown (amount).\n"
        "# Hand-priced items live here. Blocks/plants/redstone etc. are priced by the rules in blocks.yml (every obtainable block the server knows),\n"
        "# enchantment books by enchants.yml and potions by potions.yml. Prices marked EXACT in README come straight from the owner's specification.\n"
        "categories:\n")
for cid in order:
    name, icon, desc = cats[cid]
    shop += f"  {cid}:\n    name: \"{name}\"\n    icon: {icon}\n    description: [{', '.join(chr(34)+d+chr(34) for d in desc)}]\n"
    if cid in DEDUPE: shop += "    exclude-sold: true\n"
    if cid == "enchantments": shop += "    generate: enchantments\n"
    if cid == "potions": shop += "    generate: potions\n"
    rows = {"ores": ORES, "tools": TOOLS, "food": FOOD, "misc": MISC}.get(cid)
    if cid == "combat":
        shop += "    items:\n"
        for nm, tier, price in SETS:
            pieces = ", ".join(f"{tier}_{x}" for x in ("HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"))
            shop += f"      - {{set: [{pieces}], name: \"{nm}\", icon: {tier}_CHESTPLATE, price: {price}}}\n"
        for nm, tier, price, opt in EXTRA_SETS:
            pieces = ", ".join(f"{tier}_{x}" for x in ("HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"))
            shop += f"      - {{set: [{pieces}], name: \"{nm}\", icon: {tier}_CHESTPLATE, price: {price}" + (", optional: true" if opt else "") + "}\n"
        for m, a, p in COMBAT: shop += fmt_item(m, a, p) + "\n"
    elif cid == "redstone":
        shop += "    items:\n      - {material: REDSTONE, amount: 16, price: 35}\n"
    elif rows is not None:
        shop += "    items:\n" + "\n".join(fmt_item(m, a, p) for m, a, p in rows) + "\n"
    else:
        shop += "    items: []\n"
open(os.path.join(OUT, "shop.yml"), "w").write(shop)

blocks = ("# Shop pricing rules. EVERY item the running server knows that is a block (and anything matched by a rule with item: true) is listed in the shop\n"
          "# automatically: the first matching rule gives its category, price per item (unit) and pack size (amount). Pack price = round(unit x amount), minimum $1 (unit 0 = free).\n"
          "# Items matching no rule fall back to the 'fallback' price (they are named in the console at startup so you can add a rule). Anything in shop.yml is skipped here.\n"
          "# Add or tune rules freely; regexes are matched against the full UPPER_CASE material name.\n")
blocks += "exclude:\n" + "".join(f"  - '{x}'\n" for x in EXCLUDE)
blocks += f"fallback: {{category: {FALLBACK[0]}, unit: {FALLBACK[1]}, amount: {FALLBACK[2]}}}\nrules:\n"
for rx, cat, unit, amt, item in R:
    blocks += f"  - {{match: '{rx}', category: {cat}, unit: {unit}, amount: {amt}" + (", item: true" if item else "") + "}\n"
open(os.path.join(OUT, "blocks.yml"), "w").write(blocks)

# ===================== enchantments =====================
# Research notes (how each is obtained -> tier):  C = enchanting table / cheap librarian trade, U = uncommon (villager/loot, lower odds),
# R = rare (specific structure/mob/high villager level), T = treasure (not in the table: villagers, dungeon/ancient city/bastion/shipwreck chests, fishing).
ENCH = {  # level-I base price in $ ; level n costs base x (1 + 0.9 x (n-1))
 "protection": 15, "projectile_protection": 15, "feather_falling": 15, "blast_protection": 18, "fire_protection": 14, "respiration": 18, "aqua_affinity": 18,
 "thorns": 24, "depth_strider": 26, "sharpness": 15, "smite": 12, "bane_of_arthropods": 12, "knockback": 14, "fire_aspect": 20, "looting": 28,
 "sweeping_edge": 24, "efficiency": 15, "unbreaking": 18, "fortune": 45, "silk_touch": 40, "power": 15, "punch": 14, "flame": 16, "infinity": 70,
 "luck_of_the_sea": 20, "lure": 14, "loyalty": 18, "riptide": 40, "channeling": 35, "impaling": 20, "multishot": 32, "quick_charge": 18, "piercing": 14,
 "binding_curse": 30, "vanishing_curse": 30,
 "soul_speed": 130, "frost_walker": 120, "swift_sneak": 190, "mending": 450,       # treasure-only
 "density": 120, "breach": 130, "wind_burst": 160, "lunge": 110,                    # mace / spear: structure loot only
}
en = ("# Enchantment books. One book per enchantment per level is generated from the server's enchantment registry,\n"
      "# so every enchantment the server supports is sold. price(level) = base x (1 + per-level-factor x (level-1)).\n"
      "# Tiers by how the book is obtained: common (enchanting table / cheap villager trade) $12-30, uncommon $32-70,\n"
      "# rare (treasure / structure-only / mob-specific) $110-190, mending $450 (treasure only, the most sought-after book).\n"
      "per-level-factor: 0.9\ndefault-base: 120      # used (with a console warning) for any enchantment added by a newer game version\nprices:\n")
for k, v in ENCH.items(): en += f"  {k}: {v}\n"
open(os.path.join(OUT, "enchants.yml"), "w").write(en)

# ===================== potions =====================
POT = {  # base price of the normal 3-minute potion ($)   (ingredient acquisition in comment)
 "AWKWARD": 8, "MUNDANE": 8, "THICK": 8,                                 # nether wart / base ingredients
 "NIGHT_VISION": 30, "INVISIBILITY": 60,                                 # golden carrot / + fermented spider eye
 "LEAPING": 55,                                                          # rabbit's foot (rare drop)
 "FIRE_RESISTANCE": 45,                                                  # magma cream (nether)
 "SWIFTNESS": 25, "SLOWNESS": 30,                                        # sugar (easy) / + fermented eye
 "WATER_BREATHING": 30,                                                  # pufferfish
 "HEALING": 30, "HARMING": 35,                                           # glistering melon / + fermented eye
 "POISON": 25, "WEAKNESS": 20,                                           # spider eye / fermented eye
 "REGENERATION": 70,                                                     # ghast tear (nether, rare drop)
 "STRENGTH": 40,                                                         # blaze powder (nether fortress)
 "TURTLE_MASTER": 120,                                                   # turtle shell (needs baby turtles to grow)
 "SLOW_FALLING": 80,                                                     # phantom membrane (rare drop)
 "WIND_CHARGED": 90, "WEAVING": 60, "OOZING": 60, "INFESTED": 55,        # trial chamber ingredients / cobweb / slime / stone
}
po = ("# Potion shop. Every PotionType the server knows is sold as potion, splash potion and lingering potion\n"
      "# (generated from the server, so all variants are real, valid potions). price = base x variant x form.\n"
      "# base = the plain version; LONG_ x long-multiplier (redstone), STRONG_ x strong-multiplier (glowstone);\n"
      "# splash x splash-multiplier (gunpowder), lingering x lingering-multiplier (dragon's breath: end dragon only).\n"
      "long-multiplier: 1.5\nstrong-multiplier: 1.8\nsplash-multiplier: 1.3\nlingering-multiplier: 3.0\ndefault-base: 60   # used (with a console warning) for new potion types\n"
      "exclude: [WATER, LUCK]   # water bottle is free; Luck is creative-only\nprices:\n")
for k, v in POT.items(): po += f"  {k}: {v}\n"
open(os.path.join(OUT, "potions.yml"), "w").write(po)

# ===================== kits (EXACT prices) =====================
KIT_PRICE = {"starter": 0, "beginner": 75, "intermediate": 150, "advanced": 1500, "endgame": 5000}
KITS = [
 ("starter", "Starter Kit", "WOODEN_SWORD", ["&7A small free kit for new players.", "&7Enable it in config.yml: starter-kit.enabled"],
   [("WOODEN_SWORD", 1, {}), ("WOODEN_PICKAXE", 1, {}), ("BREAD", 8, {}), ("TORCH", 16, {})]),
 ("beginner", "Beginner Kit", "CHAINMAIL_CHESTPLATE", ["&7Chainmail armor, stone tools", "&7and basic supplies."],
   [("CHAINMAIL_HELMET", 1, {}), ("CHAINMAIL_CHESTPLATE", 1, {}), ("CHAINMAIL_LEGGINGS", 1, {}), ("CHAINMAIL_BOOTS", 1, {}), ("STONE_SWORD", 1, {}), ("STONE_PICKAXE", 1, {}), ("STONE_AXE", 1, {}), ("COOKED_BEEF", 16, {}), ("TORCH", 32, {}), ("OAK_PLANKS", 32, {})]),
 ("intermediate", "Intermediate Kit", "IRON_CHESTPLATE", ["&7Iron armor, iron tools", "&7and combat supplies."],
   [("IRON_HELMET", 1, {}), ("IRON_CHESTPLATE", 1, {}), ("IRON_LEGGINGS", 1, {}), ("IRON_BOOTS", 1, {}), ("IRON_SWORD", 1, {}), ("IRON_PICKAXE", 1, {}), ("IRON_AXE", 1, {}), ("IRON_SHOVEL", 1, {}), ("SHIELD", 1, {}), ("BOW", 1, {}), ("ARROW", 32, {}), ("COOKED_BEEF", 32, {}), ("GOLDEN_APPLE", 2, {})]),
 ("advanced", "Advanced Kit", "DIAMOND_CHESTPLATE", ["&7Diamond armor and tools", "&7with enchantments."],
   [("DIAMOND_HELMET", 1, {"protection": 2, "unbreaking": 2}), ("DIAMOND_CHESTPLATE", 1, {"protection": 2, "unbreaking": 2}), ("DIAMOND_LEGGINGS", 1, {"protection": 2, "unbreaking": 2}), ("DIAMOND_BOOTS", 1, {"protection": 2, "unbreaking": 2}), ("DIAMOND_SWORD", 1, {"sharpness": 2, "unbreaking": 2}), ("DIAMOND_PICKAXE", 1, {"efficiency": 3, "unbreaking": 2}), ("DIAMOND_AXE", 1, {"efficiency": 3, "unbreaking": 2}), ("SHIELD", 1, {}), ("GOLDEN_APPLE", 4, {}), ("COOKED_BEEF", 64, {}), ("ENDER_PEARL", 8, {})]),
 ("endgame", "Endgame Kit", "NETHERITE_CHESTPLATE", ["&7Full enchanted Netherite, weapons,", "&7spear (if supported), Elytra and supplies."],
   [("NETHERITE_HELMET", 1, {"protection": 4, "unbreaking": 3, "mending": 1, "respiration": 3, "aqua_affinity": 1}), ("NETHERITE_CHESTPLATE", 1, {"protection": 4, "unbreaking": 3, "mending": 1}), ("NETHERITE_LEGGINGS", 1, {"protection": 4, "unbreaking": 3, "mending": 1}), ("NETHERITE_BOOTS", 1, {"protection": 4, "unbreaking": 3, "mending": 1, "feather_falling": 4, "depth_strider": 3}), ("NETHERITE_SWORD", 1, {"sharpness": 5, "unbreaking": 3, "mending": 1, "looting": 3, "fire_aspect": 2}), ("NETHERITE_AXE", 1, {"sharpness": 5, "efficiency": 5, "unbreaking": 3, "mending": 1}), ("NETHERITE_PICKAXE", 1, {"efficiency": 5, "unbreaking": 3, "mending": 1, "fortune": 3}), ("NETHERITE_SHOVEL", 1, {"efficiency": 5, "unbreaking": 3, "mending": 1}), ("NETHERITE_SPEAR", 1, {"unbreaking": 3, "mending": 1}), ("BOW", 1, {"power": 5, "unbreaking": 3, "mending": 1, "infinity": 1}), ("ELYTRA", 1, {"unbreaking": 3, "mending": 1}), ("FIREWORK_ROCKET", 64, {}), ("SHIELD", 1, {"unbreaking": 3, "mending": 1}), ("ENCHANTED_GOLDEN_APPLE", 8, {}), ("TOTEM_OF_UNDYING", 1, {}), ("ENDER_PEARL", 16, {}), ("COOKED_BEEF", 64, {})]),
]
kt = ("# Kits - prices in Dollars ($). price 0 = free. cooldown-seconds 0 = no cooldown.\n"
      "# Prices are EXACT from the server owner's specification: Starter free, Beginner $75, Intermediate $150, Advanced $1,500, Endgame $5,000.\n"
      "# Items whose material does not exist on this server version (e.g. spears) are skipped with a console warning.\nkits:\n")
for kid, name, icon, desc, items in KITS:
    kt += f"  {kid}:\n    name: {name}\n    icon: {icon}\n    description: [{', '.join(chr(34)+d+chr(34) for d in desc)}]\n    price: {KIT_PRICE[kid]}\n    cooldown-seconds: 0\n    items:\n"
    for m, a, e in items:
        parts = [f"material: {m}"] + ([f"amount: {a}"] if a > 1 else []) + (["enchants: {" + ", ".join(f"{x}: {y}" for x, y in e.items()) + "}"] if e else [])
        kt += "      - {" + ", ".join(parts) + "}\n"
open(os.path.join(OUT, "kits.yml"), "w").write(kt)

# ===================== market (sell side) =====================
SHOP_UNIT = {}
for m, a, p in ORES + FOOD + MISC + TOOLS + COMBAT: SHOP_UNIT.setdefault(m, p / a)
def unit_price(m):
    if m in SHOP_UNIT: return SHOP_UNIT[m]
    r = apply_rules(m)
    if r and r[1] > 0: return max(1, round(r[1] * r[2])) / r[2]   # pack price / pack size = effective shop unit price
    return None
MARKET = {
 "nature": ("Nature", "OAK_SAPLING", [("BAMBOO", 16), ("SAND", 32), ("DIRT", 64), ("OAK_LOG", 16), ("SPRUCE_LOG", 16), ("BIRCH_LOG", 16), ("OAK_LEAVES", 32), ("GRAVEL", 32), ("CLAY_BALL", 16), ("KELP", 16), ("SUGAR_CANE", 16), ("CACTUS", 16)]),
 "mining": ("Mining", "IRON_PICKAXE", [("COBBLESTONE", 64), ("STONE", 32), ("REDSTONE", 16), ("COAL", 16), ("RAW_IRON", 8), ("IRON_INGOT", 8), ("GOLD_INGOT", 8), ("LAPIS_LAZULI", 16), ("DIAMOND", 1), ("EMERALD", 4), ("QUARTZ", 16), ("COPPER_INGOT", 16)]),
 "farming": ("Farming", "WHEAT", [("WHEAT", 16), ("CARROT", 16), ("POTATO", 16), ("BEETROOT", 16), ("PUMPKIN", 8), ("MELON_SLICE", 32), ("SWEET_BERRIES", 16), ("COCOA_BEANS", 16), ("NETHER_WART", 16)]),
 "mob-drops": ("Mob Drops", "ROTTEN_FLESH", [("ROTTEN_FLESH", 16), ("BONE", 16), ("STRING", 16), ("SPIDER_EYE", 16), ("GUNPOWDER", 16), ("ENDER_PEARL", 4), ("BLAZE_ROD", 4), ("SLIME_BALL", 8), ("LEATHER", 8), ("FEATHER", 16)]),
 "misc": ("Miscellaneous", "CHEST", [("GLASS_BOTTLE", 16), ("PAPER", 32), ("SNOWBALL", 16), ("FLINT", 16), ("INK_SAC", 16), ("NAUTILUS_SHELL", 1), ("PRISMARINE_SHARD", 8)]),
}
EXTRA_P = {"SPRUCE_LOG": 0.6, "BIRCH_LOG": 0.6}
SELL_FACTOR = 0.35
mt = ("# Dynamic selling market - all payouts in Dollars ($).\n"
      "# price = BASE payout per `bundle` units = shop value x 0.35 (min $1). On refresh each item rolls UP/DOWN and its price is multiplied (x0.4 - x2.0),\n"
      "# so even a x2.0 day pays well below the shop's buy price: buying and re-selling can never make money.\n"
      "refresh-hours: 24\nannounce-refresh: true\nmax-sale-bundles: 64       # most bundles one click can sell\nup-chance: 0.5\n"
      "up-multiplier:   {min: 1.3, max: 2.0}\ndown-multiplier: {min: 0.4, max: 0.8}\nstock:                     # bundles the market will buy per refresh\n"
      "  up:   {min: 64, max: 256}\n  down: {min: 16, max: 96}\n\ncategories:\n")
report = []
for cid, (name, icon, items) in MARKET.items():
    mt += f"  {cid}:\n    name: {name}\n    icon: {icon}\n    items:\n"
    for m, b in items:
        u = unit_price(m) or EXTRA_P.get(m) or {"ROTTEN_FLESH": 0.5, "INK_SAC": 1.0, "SNOWBALL": 0.375, "FLINT": 0.75}.get(m)
        assert u, f"no unit price for {m}"
        price = max(1, round(u * b * SELL_FACTOR))
        mt += f"      - {{material: {m}, bundle: {b}, price: {price}}}\n"
        report.append((m, b, price, u))
open(os.path.join(OUT, "market.yml"), "w").write(mt)
json.dump({"market": report}, open("/tmp/market_report.json", "w"))
print("generated into", OUT, "| rules:", len(R))
