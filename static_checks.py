"""Repository + pricing checks (no server needed). Usage: python3 static_checks.py <repo-root>   (nested or flat layout)"""
import os, re, sys, yaml
root = sys.argv[1] if len(sys.argv) > 1 else "."
java, res = {}, {}
for d, _, fs in os.walk(root):
    if ".git" in d: continue
    for f in fs:
        p = os.path.join(d, f)
        if f.endswith(".java"): java[f] = open(p).read()
        if f == "build.gradle.kts": res[f] = open(p).read()
        if f in ("plugin.yml", "config.yml", "shop.yml", "kits.yml", "market.yml", "blocks.yml", "enchants.yml", "potions.yml"): res[f] = open(p).read()
allj = "\n".join(java.values())
y = {k: yaml.safe_load(v) for k, v in res.items() if k.endswith(".yml")}
passed = failed = 0
def check(n, c, d=""):
    global passed, failed
    if c: passed += 1; print("  PASS ", n)
    else: failed += 1; print("  FAIL ", n, d)

print("== removed features stay removed; requested ones present")
cmds = set(y["plugin.yml"]["commands"])
check("/rtp, /tpa, /tpaccept, /tpdeny, /back, /spawn, /setspawn are NOT registered", not (cmds & {"rtp", "tpa", "tpaccept", "tpdeny", "back", "spawn", "setspawn"}), str(cmds))
check("/sethome, /home, /deletehome ARE registered", {"sethome", "home", "deletehome"} <= cmds)
check("no random-teleport code anywhere (no Random / rtp)", not re.search(r"ThreadLocalRandom|\brtp\b|getChunkAtAsync|randomLocation", java["HomeManager.java"] + java["Commands.java"], re.I))
check("old TeleportManager / Currency classes are gone", "TeleportManager.java" not in java and "Currency.java" not in java and "Currency" not in allj)
check("home handlers exist in Commands.java", all(f'"{c}"' in java["Commands.java"] for c in ("sethome", "home", "deletehome")))
check("/home is blocked in combat (HomeManager + default blocked-commands)", "inCombat" in java["HomeManager.java"] and "home" in y["config.yml"]["combat"]["blocked-commands"])
check("home is persisted via HOME_SET/HOME_DELETE and validated (invalid())", "Sql.HOME_SET" in java["HomeManager.java"] and "Sql.HOME_DELETE" in java["HomeManager.java"] and "invalid(" in java["HomeManager.java"])
check("a home save that fails is never reported as success", "home-save-failed" in java["HomeManager.java"])

print("== commands / permissions / messages")
perms = set(y["plugin.yml"]["permissions"]); children = {k for p in y["plugin.yml"]["permissions"].values() if isinstance(p, dict) for k in (p.get("children") or {})}
for c, v in y["plugin.yml"]["commands"].items():
    check(f"/{c} has a handler and a declared permission", f'"{c}"' in java["Commands.java"] and (v["permission"] in perms or v["permission"] in children))
used = {a or b for a, b in re.findall(r'perm\(s, "([\w.]+)"\)|hasPermission\("([\w.]+)"\)', allj)}
check("every permission checked in code is declared", used <= (perms | children), str(used - perms - children))
keys = set(y["config.yml"]["messages"])
code_keys = set(re.findall(r'(?:send|broadcast|msg|raw)\((?:[\w.()]+, )?"([a-z0-9-]+)"', allj))
code_keys |= set(re.findall(r'"((?:sell|shop|kit|pay|tx|bounty|combat|balance|baltop|admin|migrate|market|player|invalid|usage|reloaded|db|no|wanted|home)-[a-z0-9-]+)"', allj))
code_keys |= set(re.findall(r'"(home-[a-z-]+)"', java["HomeManager.java"]))
missing = {k for k in code_keys if k not in keys and k != "admin-"}
check("every message key used in code exists in config.yml", not missing, str(sorted(missing)))

print("== starting balance / HUD config")
cfg = y["config.yml"]
check("starting balance is $150 and is granted by INSERT OR IGNORE at account creation only", cfg["economy"]["starting-balance"] == 150 and "INSERT OR IGNORE INTO accounts" in java["Sql.java"] and "starting-balance" in java["EconomyManager.java"])
check("HUD shows money, bounty and kills", all(p in "\n".join(cfg["hud"]["lines"]) for p in ("%money%", "%bounty%", "%kills%")) and cfg["hud"]["enabled"] is True)
check("HUD refreshes on money/bounty/kill changes (touch hooks present)", java["EconomyManager.java"].count("hud.touch") >= 3 and "hud.touch" in java["BountyManager.java"])
check("HUD only re-sends changed lines and batches per tick", "equals(b.last[i])" in java["HudManager.java"] and "dirty.add(id)" in java["HudManager.java"])

print("== EXACT kit prices")
kits = y["kits.yml"]["kits"]
for k, v in {"starter": 0, "beginner": 75, "intermediate": 150, "advanced": 1500, "endgame": 5000}.items():
    check(f"kit {k} = ${v}", kits[k]["price"] == v, str(kits[k]["price"]))
check("kit GUI shows k.price() and the purchase charges k.price() (same field)", "Money.fmt(k.price())" in java["KitMenu.java"] and "-k.price()" in java["KitManager.java"] and "a.dollars < k.price()" in java["KitManager.java"])

print("== EXACT ore & TNT table")
shop = y["shop.yml"]["categories"]
ores = {i["material"]: (i.get("amount", 1), i["price"]) for i in shop["ores"]["items"]}
for m, a, p in (("COAL", 16, 15), ("RAW_IRON", 8, 25), ("IRON_INGOT", 8, 25), ("GOLD_INGOT", 8, 20), ("COPPER_INGOT", 16, 15), ("LAPIS_LAZULI", 32, 50), ("REDSTONE", 16, 35),
                ("DIAMOND", 16, 185), ("EMERALD", 4, 15), ("ANCIENT_DEBRIS", 2, 750), ("NETHERITE_SCRAP", 2, 500), ("TNT", 32, 75)):
    check(f"{m} x{a} = ${p}", ores.get(m) == (a, p), str(ores.get(m)))
check("GUI shows it.price() and purchase charges it.price() x qty (no hidden multiplier)", "Money.price(it.price())" in java["ShopItemsMenu.java"] and "multiplyExact(it.price(), (long) qty)" in java["ShopManager.java"])
check("a pack's price is charged once per pack (delivery uses amount, charge uses price)", "(long) s.getAmount() * qty" in java["ShopManager.java"])

print("== EXACT combat prices")
combat = shop["combat"]["items"]
sets = {i["name"]: i for i in combat if "set" in i}
for name, tier, price in (("Full Iron Armor Set", "IRON", 150), ("Full Diamond Armor Set", "DIAMOND", 250), ("Full Netherite Armor Set", "NETHERITE", 3500)):
    it = sets[name]
    check(f"{name} = ${price} with helmet, chestplate, leggings, boots", it["price"] == price and it["set"] == [f"{tier}_{x}" for x in ("HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS")], str(it))
cp = {i["material"]: i["price"] for i in combat if "material" in i}
for m, p in (("IRON_SWORD", 35), ("DIAMOND_SWORD", 450), ("NETHERITE_SWORD", 1400)): check(f"{m} = ${p}", cp.get(m) == p, str(cp.get(m)))
bl = y["blocks.yml"]
trim = [r for r in bl["rules"] if "ARMOR_TRIM" in r["match"]]
check("armor trims are FREE (price 0) and listed under Combat", len(trim) == 1 and trim[0]["unit"] == 0 and trim[0]["category"] == "combat" and trim[0].get("item") is True)
check("free items are limited to 1 per click and need no charge", "if (it.price() <= 0) return 1;" in java["ShopManager.java"] and "if (total == 0)" in java["ShopManager.java"])

print("== block/nature shop rules")
def rule_for(name):
    for r in bl["rules"]:
        if re.fullmatch(r["match"], name): return r
    return None
excl = [re.compile(x) for x in bl["exclude"]]
def universe():
    N = set()
    WOODS = "OAK SPRUCE BIRCH JUNGLE ACACIA DARK_OAK MANGROVE CHERRY PALE_OAK BAMBOO CRIMSON WARPED".split()
    COLORS = "WHITE ORANGE MAGENTA LIGHT_BLUE YELLOW LIME PINK GRAY LIGHT_GRAY CYAN PURPLE BLUE BROWN GREEN RED BLACK".split()
    for w in WOODS:
        for s in ("PLANKS", "STAIRS", "SLAB", "FENCE", "FENCE_GATE", "DOOR", "TRAPDOOR", "SIGN", "HANGING_SIGN", "BUTTON", "PRESSURE_PLATE"): N.add(f"{w}_{s}")
        if w not in ("BAMBOO",):
            for s in ("LOG", "WOOD", "STRIPPED_LOG", "STRIPPED_WOOD", "LEAVES", "SAPLING"):
                if w in ("CRIMSON", "WARPED"):
                    N.update({f"{w}_STEM", f"{w}_HYPHAE", f"STRIPPED_{w}_STEM", f"STRIPPED_{w}_HYPHAE"})
                elif s.startswith("STRIPPED"): N.add(f"STRIPPED_{w}_{s.split('_')[1]}")
                elif w != "CRIMSON" and w != "WARPED": N.add(f"{w}_{s}")
    N |= {"BAMBOO_MOSAIC", "BAMBOO_MOSAIC_STAIRS", "BAMBOO_MOSAIC_SLAB", "BAMBOO_BLOCK", "STRIPPED_BAMBOO_BLOCK", "MANGROVE_ROOTS", "MUDDY_MANGROVE_ROOTS", "MANGROVE_PROPAGULE", "AZALEA", "FLOWERING_AZALEA", "AZALEA_LEAVES", "FLOWERING_AZALEA_LEAVES"}
    for c in COLORS:
        for s in ("WOOL", "CARPET", "CONCRETE", "CONCRETE_POWDER", "TERRACOTTA", "GLAZED_TERRACOTTA", "STAINED_GLASS", "STAINED_GLASS_PANE", "BANNER", "BED", "CANDLE", "SHULKER_BOX"): N.add(f"{c}_{s}")
    N |= {"TERRACOTTA", "GLASS", "GLASS_PANE", "TINTED_GLASS", "CANDLE", "SHULKER_BOX"}
    stones = "COBBLESTONE MOSSY_COBBLESTONE STONE ANDESITE DIORITE GRANITE TUFF CALCITE DRIPSTONE_BLOCK DEEPSLATE COBBLED_DEEPSLATE POLISHED_ANDESITE POLISHED_DIORITE POLISHED_GRANITE POLISHED_TUFF POLISHED_DEEPSLATE SMOOTH_STONE BRICKS STONE_BRICKS MOSSY_STONE_BRICKS CRACKED_STONE_BRICKS CHISELED_STONE_BRICKS DEEPSLATE_BRICKS DEEPSLATE_TILES TUFF_BRICKS MUD_BRICKS SANDSTONE RED_SANDSTONE SMOOTH_SANDSTONE CUT_SANDSTONE CHISELED_SANDSTONE".split()
    for s in stones:
        N.add(s)
        for sh in ("STAIRS", "SLAB", "WALL"): N.add(f"{s[:-1] if s.endswith('S') and s not in ('TUFF',) else s}_{sh}" if s.endswith("BRICKS") or s.endswith("TILES") else f"{s}_{sh}")
    N |= {"NETHERRACK", "NETHER_BRICKS", "RED_NETHER_BRICKS", "CRACKED_NETHER_BRICKS", "CHISELED_NETHER_BRICKS", "NETHER_BRICK_STAIRS", "NETHER_BRICK_SLAB", "NETHER_BRICK_WALL", "NETHER_BRICK_FENCE", "BLACKSTONE", "POLISHED_BLACKSTONE", "POLISHED_BLACKSTONE_BRICKS", "BASALT", "POLISHED_BASALT", "SMOOTH_BASALT", "GILDED_BLACKSTONE", "SOUL_SAND", "SOUL_SOIL", "GLOWSTONE", "MAGMA_BLOCK", "NETHER_WART_BLOCK", "WARPED_WART_BLOCK", "SHROOMLIGHT", "BONE_BLOCK", "END_STONE", "END_STONE_BRICKS", "PURPUR_BLOCK", "PURPUR_PILLAR", "PURPUR_STAIRS", "PURPUR_SLAB", "CHORUS_FLOWER", "QUARTZ_BLOCK", "QUARTZ_BRICKS", "QUARTZ_PILLAR", "CHISELED_QUARTZ_BLOCK", "SMOOTH_QUARTZ", "OBSIDIAN", "CRYING_OBSIDIAN", "DRAGON_EGG", "BEACON", "CONDUIT", "NETHERITE_BLOCK", "DIAMOND_BLOCK", "EMERALD_BLOCK", "GOLD_BLOCK", "IRON_BLOCK", "LAPIS_BLOCK", "COAL_BLOCK", "REDSTONE_BLOCK", "RAW_IRON_BLOCK", "RAW_GOLD_BLOCK", "RAW_COPPER_BLOCK", "AMETHYST_BLOCK", "AMETHYST_CLUSTER", "COPPER_BLOCK", "EXPOSED_COPPER", "WEATHERED_COPPER", "OXIDIZED_COPPER", "CUT_COPPER", "WAXED_COPPER_BLOCK", "WAXED_OXIDIZED_CUT_COPPER_STAIRS", "CHISELED_COPPER", "COPPER_GRATE", "COPPER_DOOR", "COPPER_TRAPDOOR", "COPPER_BULB", "LIGHTNING_ROD"}
    N |= {"DIRT", "GRASS_BLOCK", "PODZOL", "MYCELIUM", "COARSE_DIRT", "ROOTED_DIRT", "DIRT_PATH", "MUD", "PACKED_MUD", "CLAY", "GRAVEL", "SAND", "RED_SAND", "SNOW", "SNOW_BLOCK", "ICE", "PACKED_ICE", "BLUE_ICE", "SPONGE", "WET_SPONGE", "COBWEB", "SCULK", "SCULK_VEIN", "SCULK_CATALYST", "SCULK_SHRIEKER", "SCULK_SENSOR", "TURTLE_EGG", "SNIFFER_EGG", "PRISMARINE", "PRISMARINE_BRICKS", "DARK_PRISMARINE", "SEA_LANTERN", "BEE_NEST", "BEEHIVE", "HONEYCOMB_BLOCK"}
    for cr in ("TUBE", "BRAIN", "BUBBLE", "FIRE", "HORN"):
        N |= {f"{cr}_CORAL", f"{cr}_CORAL_BLOCK", f"{cr}_CORAL_FAN", f"DEAD_{cr}_CORAL", f"DEAD_{cr}_CORAL_BLOCK", f"DEAD_{cr}_CORAL_FAN"}
    N |= {"DANDELION", "POPPY", "BLUE_ORCHID", "ALLIUM", "AZURE_BLUET", "RED_TULIP", "ORANGE_TULIP", "WHITE_TULIP", "PINK_TULIP", "OXEYE_DAISY", "CORNFLOWER", "LILY_OF_THE_VALLEY", "TORCHFLOWER", "PINK_PETALS", "WITHER_ROSE", "SUNFLOWER", "LILAC", "ROSE_BUSH", "PEONY", "PITCHER_PLANT", "SPORE_BLOSSOM", "SHORT_GRASS", "TALL_GRASS", "FERN", "LARGE_FERN", "DEAD_BUSH", "SEAGRASS", "KELP", "VINE", "LILY_PAD", "GLOW_LICHEN", "WEEPING_VINES", "TWISTING_VINES", "HANGING_ROOTS", "BIG_DRIPLEAF", "SMALL_DRIPLEAF", "SUGAR_CANE", "BAMBOO", "CACTUS", "MOSS_BLOCK", "MOSS_CARPET", "SEA_PICKLE", "WHEAT_SEEDS", "PUMPKIN_SEEDS", "MELON_SEEDS", "BEETROOT_SEEDS", "WHEAT", "CARROT", "POTATO", "BEETROOT", "MELON_SLICE", "SWEET_BERRIES", "GLOW_BERRIES", "COCOA_BEANS", "NETHER_WART", "CRIMSON_FUNGUS", "WARPED_FUNGUS", "RED_MUSHROOM", "BROWN_MUSHROOM", "PUMPKIN", "MELON", "HAY_BLOCK", "DRIED_KELP_BLOCK", "RED_MUSHROOM_BLOCK", "BROWN_MUSHROOM_BLOCK", "MUSHROOM_STEM", "COAL_ORE", "DEEPSLATE_DIAMOND_ORE", "NETHER_QUARTZ_ORE", "NETHER_GOLD_ORE"}
    N |= {"PISTON", "STICKY_PISTON", "OBSERVER", "HOPPER", "DROPPER", "DISPENSER", "LEVER", "REPEATER", "COMPARATOR", "REDSTONE_LAMP", "TARGET", "DAYLIGHT_DETECTOR", "REDSTONE_TORCH", "NOTE_BLOCK", "TRIPWIRE_HOOK", "TRAPPED_CHEST", "CRAFTER", "SLIME_BLOCK", "HONEY_BLOCK", "RAIL", "POWERED_RAIL", "DETECTOR_RAIL", "ACTIVATOR_RAIL", "STONE_PRESSURE_PLATE", "STONE_BUTTON", "HEAVY_WEIGHTED_PRESSURE_PLATE", "LIGHT_WEIGHTED_PRESSURE_PLATE", "POLISHED_BLACKSTONE_BUTTON"}
    N |= {"CRAFTING_TABLE", "CHEST", "BARREL", "BOOKSHELF", "LECTERN", "FURNACE", "BLAST_FURNACE", "SMOKER", "CAMPFIRE", "ANVIL", "CHIPPED_ANVIL", "ENCHANTING_TABLE", "ENDER_CHEST", "LODESTONE", "RESPAWN_ANCHOR", "BELL", "JUKEBOX", "LANTERN", "SOUL_LANTERN", "TORCH", "SOUL_TORCH", "END_ROD", "FLOWER_POT", "DECORATED_POT", "JACK_O_LANTERN", "LADDER", "SCAFFOLDING", "CHAIN", "IRON_BARS", "IRON_DOOR", "IRON_TRAPDOOR", "OCHRE_FROGLIGHT", "WITHER_SKELETON_SKULL", "DRAGON_HEAD", "CREEPER_HEAD", "PLAYER_HEAD", "HEAVY_CORE", "LOOM", "GRINDSTONE", "STONECUTTER", "COMPOSTER", "CAULDRON", "BREWING_STAND", "SMITHING_TABLE", "CARTOGRAPHY_TABLE", "FLETCHING_TABLE", "CHISELED_BOOKSHELF", "CARVED_PUMPKIN"}
    for t in ("SENTRY", "DUNE", "COAST", "WILD", "WARD", "EYE", "VEX", "TIDE", "SNOUT", "RIB", "SPIRE", "SILENCE", "RAISER", "HOST", "SHAPER", "WAYFINDER", "FLOW", "BOLT"): N.add(f"{t}_ARMOR_TRIM_SMITHING_TEMPLATE")
    return N
U = universe()
unmatched = [n for n in sorted(U) if not any(x.match(n) for x in excl) and rule_for(n) is None]
check(f"sample of {len(U)} block/item names: nearly all are priced by an explicit rule (rest use the fallback)", len(unmatched) <= 0.03 * len(U), f"{len(unmatched)} unmatched: {unmatched[:25]}")
cats = set(shop.keys())
bad = [n for n in U if rule_for(n) and rule_for(n)["category"] not in cats]
check("every rule points at a category defined in shop.yml", not bad and all(r["category"] in cats for r in bl["rules"]) and bl["fallback"]["category"] in cats, str(bad[:5]))
check("unobtainable blocks are excluded (bedrock, barrier, command blocks, spawner, infested...)", all(any(x.match(n) for x in excl) for n in ("BEDROCK", "BARRIER", "COMMAND_BLOCK", "CHAIN_COMMAND_BLOCK", "SPAWNER", "INFESTED_STONE", "STRUCTURE_BLOCK", "END_PORTAL_FRAME", "REINFORCED_DEEPSLATE")))
def pack(n):
    r = rule_for(n); return None if r is None else (r["category"], max(1, round(r["unit"] * r["amount"])) if r["unit"] > 0 else 0, r["amount"])
check("Nature is complete: logs, leaves, saplings, flowers, mushrooms, vines, moss, grass, crops, terrain all land in nature_* categories",
      all((pack(n) or ("",))[0].startswith("nature") for n in ("OAK_LOG", "STRIPPED_CHERRY_LOG", "CRIMSON_STEM", "OAK_LEAVES", "OAK_SAPLING", "DANDELION", "RED_MUSHROOM", "VINE", "MOSS_BLOCK", "SHORT_GRASS", "WHEAT", "CARROT", "DIRT", "SAND", "PACKED_ICE", "BRAIN_CORAL", "SCULK", "KELP", "LILY_PAD", "RED_MUSHROOM_BLOCK")))
check("rare blocks cost far more than common ones", pack("NETHERITE_BLOCK")[1] > pack("DIAMOND_BLOCK")[1] > pack("IRON_BLOCK")[1] > pack("COBBLESTONE")[1] and pack("DRAGON_EGG")[1] > 3000 and pack("BEACON")[1] > pack("ENCHANTING_TABLE")[1] > pack("CRAFTING_TABLE")[1] and pack("WITHER_ROSE")[1] > pack("POPPY")[1])
check("common nature is moderate (a stack of logs/sand/dirt costs $1-$20)", all(1 <= pack(n)[1] <= 20 for n in ("OAK_LOG", "SAND", "DIRT", "OAK_LEAVES", "GRAVEL", "DANDELION")), str([(n, pack(n)) for n in ("OAK_LOG", "SAND", "DIRT")]))
check("every priced rule is >= 0 and only armor trims are free", all(r["unit"] > 0 for r in bl["rules"] if "ARMOR_TRIM" not in r["match"]))
check("every pack price is a whole number >= $1 (except free trims)", all(pack(n)[1] >= 1 for n in U if pack(n) and "ARMOR_TRIM" not in n))

print("== enchantment books")
VANILLA_ENCH = "protection fire_protection feather_falling blast_protection projectile_protection respiration aqua_affinity thorns depth_strider frost_walker binding_curse soul_speed swift_sneak sharpness smite bane_of_arthropods knockback fire_aspect looting sweeping_edge efficiency silk_touch unbreaking fortune power punch flame infinity luck_of_the_sea lure loyalty impaling riptide channeling multishot quick_charge piercing density breach wind_burst mending vanishing_curse lunge".split()
ep = y["enchants.yml"]["prices"]
check(f"all {len(VANILLA_ENCH)} known enchantments (incl. treasure/curses) have an explicit price", all(k in ep for k in VANILLA_ENCH), str([k for k in VANILLA_ENCH if k not in ep]))
check("prices are tiered, not flat (>= 12 distinct values)", len(set(ep.values())) >= 12, str(len(set(ep.values()))))
check("treasure/restricted books cost far more than common ones (mending > soul_speed > fortune > sharpness)", ep["mending"] > ep["soul_speed"] > ep["fortune"] > ep["sharpness"] and ep["swift_sneak"] > ep["efficiency"] * 5)
check("per-level pricing rises with level (level V > level I) and generation is bounded by each enchantment's own max level", y["enchants.yml"]["per-level-factor"] > 0 and "getMaxLevel()" in java["ShopManager.java"] and "addStoredEnchant(en, lvl, false)" in java["ShopManager.java"])
check("unknown (newer) enchantments are still sold at default-base with a warning", "default-base" in res["enchants.yml"] and "no price for enchantment" in java["ShopManager.java"])

print("== potions")
VANILLA_POT = "WATER MUNDANE THICK AWKWARD NIGHT_VISION LONG_NIGHT_VISION INVISIBILITY LONG_INVISIBILITY LEAPING LONG_LEAPING STRONG_LEAPING FIRE_RESISTANCE LONG_FIRE_RESISTANCE SWIFTNESS LONG_SWIFTNESS STRONG_SWIFTNESS SLOWNESS LONG_SLOWNESS STRONG_SLOWNESS WATER_BREATHING LONG_WATER_BREATHING HEALING STRONG_HEALING HARMING STRONG_HARMING POISON LONG_POISON STRONG_POISON REGENERATION LONG_REGENERATION STRONG_REGENERATION STRENGTH LONG_STRENGTH STRONG_STRENGTH WEAKNESS LONG_WEAKNESS LUCK TURTLE_MASTER LONG_TURTLE_MASTER STRONG_TURTLE_MASTER SLOW_FALLING LONG_SLOW_FALLING STRONG_SLOW_FALLING WIND_CHARGED WEAVING OOZING INFESTED".split()
pp = y["potions.yml"]["prices"]; ex = {e.upper() for e in y["potions.yml"]["exclude"]}
def base(n): return n[5:] if n.startswith("LONG_") else n[7:] if n.startswith("STRONG_") else n
check("every potion type has a priced base effect (or is excluded)", all(base(n) in pp or n in ex for n in VANILLA_POT), str([n for n in VANILLA_POT if base(n) not in pp and n not in ex]))
check("rare-ingredient potions cost more than easy ones (turtle master > slow falling > regeneration > swiftness)", pp["TURTLE_MASTER"] > pp["SLOW_FALLING"] > pp["REGENERATION"] > pp["SWIFTNESS"])
pt = y["potions.yml"]
check("variants scale: lingering > splash > normal, strong/long > base", pt["lingering-multiplier"] > pt["splash-multiplier"] > 1 and pt["strong-multiplier"] > 1 and pt["long-multiplier"] > 1)
check("potions are generated from the server's PotionType list as real potions (setBasePotionType, POTION/SPLASH_POTION/LINGERING_POTION)", "PotionType.values()" in java["ShopManager.java"] and "setBasePotionType(type)" in java["ShopManager.java"] and all(m in java["ShopManager.java"] for m in ("Material.POTION", "Material.SPLASH_POTION", "Material.LINGERING_POTION")))
check("not every potion costs the same (>= 10 distinct base prices)", len(set(pp.values())) >= 10)

print("== market vs shop: no buy-and-resell profit; prices in dollars")
mk = y["market.yml"]
mats = {i["material"]: i for c in mk["categories"].values() for i in c["items"]}
maxmult = mk["up-multiplier"]["max"]
def shop_unit(m):
    for c in shop.values():
        for i in c["items"]:
            if i.get("material") == m: return i["price"] / i.get("amount", 1)
    r = rule_for(m)
    if r and r["unit"] > 0: return max(1, round(r["unit"] * r["amount"])) / r["amount"]
    return None
viol, checked = [], 0
for m, i in mats.items():
    u = shop_unit(m)
    if u is None: continue
    checked += 1
    if (i["price"] / i["bundle"]) * maxmult >= u: viol.append((m, round(i["price"] / i["bundle"] * maxmult, 3), round(u, 3)))
check(f"even at the max x{maxmult} multiplier every sellable item pays less per unit than the shop charges ({checked} items compared)", not viol, str(viol))
check("market keeps 24h refresh, UP/DOWN multipliers, stock, and >= 5 categories", mk["refresh-hours"] == 24 and "up-multiplier" in mk and "down-multiplier" in mk and "stock" in mk and len(mk["categories"]) >= 5)
check("carrots (the reported bug item) sell and 12 stacks pay > $0", "CARROT" in mats and (12 * 64 // mats["CARROT"]["bundle"]) * mats["CARROT"]["price"] > 0)
check("a new player's $150 is a playable start: Beginner kit ($75) and a basic iron tool set are affordable", kits["beginner"]["price"] < 150 and cp["IRON_SWORD"] + 40 < 150)

print("== purchase safety in the code")
sm = java["ShopManager.java"]
check("every shop entry (blocks, books, potions, sets, free) goes through ONE purchase() path", sm.count("public void purchase(") == 1 and "plugin.shop.purchase(" in java["PurchaseMenu.java"])
check("purchase: space + funds checked, lock taken, charge committed before delivery, refund on failed delivery", all(x in sm for x in ("canFit(p, stacks)", "a.dollars < total", "tryLock", "SHOP_REFUND", "plugin.economy.commit(\"SHOP\"")))
check("GUI clicks can never move items (menu clicks cancelled)", "e.setCancelled(true)" in java["Listeners.java"] and "InventoryDragEvent" in java["Listeners.java"])

print("== migration / database")
check("Database makes a backup before upgrading and before migrating", "backup(conn, \"pre-upgrade\")" in java["Database.java"] and "pre-migration" in java["EconomyManager.java"])
check("migration never runs silently: rates default to -1", cfg["migration"]["iron-rate"] == -1 and cfg["migration"]["diamond-rate"] == -1)
check("combat log kept (quit punishment + shutdown guard)", "isStopping()" in java["Listeners.java"] and "setHealth(0.0)" in java["Listeners.java"])

print("== 2.0.0: version, commands, stats, payments")
check("project version is 2.0.0 and the jar is named from it", 'version = "2.0.0"' in res["build.gradle.kts"] and "ModsForKnightSMP-${project.version}.jar" in res["build.gradle.kts"])
check("plugin.yml takes its version from the build (expanded to 2.0.0) and has a real main class", y["plugin.yml"]["version"] == "${version}" and y["plugin.yml"]["main"] == "dev.knightsmp.mods.KnightPlugin" and "expand(\"version\"" in res["build.gradle.kts"])
check("/stat, /pay and /payanim are registered with handlers", {"stat", "pay", "payanim"} <= cmds and all(f'"{c}"' in java["Commands.java"] for c in ("stat", "pay", "payanim")))
sm, st = java["StatMenu.java"], java["StatsManager.java"]
check("/stat shows money, kills, deaths, playtime, streaks, bounty, ranks, trading totals - all read from saved data", all(x in sm for x in ("a.dollars", "a.kills", "a.deaths", "plugin.stats.playtime(a)", "a.bestStreak", "a.bounty", "extra.moneyRank()", "extra.sum(\"SELL\")")))
check("/stat has no hardcoded statistic values (every number printed comes from a variable)", not re.search(r'Text\.num\(\d+\)|"&8[A-Za-z /]+: &f\d+"', sm))
check("deaths, streaks and playtime are persisted (SQL), playtime saved on quit, on a timer and on shutdown", all(x in st for x in ("Sql.DEATH_ADD", "Sql.PLAYTIME_ADD", "flushAll", "public void onQuit", "public void shutdown")) and "stats.onQuit(p)" in java["Listeners.java"] and "stats.shutdown()" in java["KnightPlugin.java"])
check("every death (including combat-log kills) is counted", "plugin.stats.onDeath(victim)" in java["Listeners.java"])
ep = java["EconomyManager.java"]
pay_body = ep[ep.index("public void pay("):ep.index("// ---------- admin")]
check("/pay validates amount, self-payment, balance, recipient cap and takes the per-player lock", all(x in pay_body for x in ("amt <= 0", "fa.id.equals(to.id)", "fa.dollars < amt", "to.dollars > cap() - amt", "tryLock")))
check("/pay recipient is validated (must be an online or previously seen player with an account)", "resolve(a[0])" in java["Commands.java"] and "withAccount" in java["Commands.java"] and "player-not-found" in java["Commands.java"])
check("/pay amounts are strict whole numbers: negative, zero, decimals and letters are rejected", "v > 0 ? v : -1" in java["Commands.java"])
ok_i, fail_i, anim_i = pay_body.index("if (ok) {"), pay_body.index('"pay-failed"'), pay_body.index("plugin.fx.play")
check("success messages AND the animation only happen inside the committed branch; failure says nothing succeeded", ok_i < anim_i < fail_i and pay_body.index('"pay-sent"') < fail_i and pay_body.index('"pay-sent"') > ok_i)
check("the payment animation can never break a payment (wrapped in try/catch)", "try { plugin.fx.play" in pay_body and "catch (Exception e)" in pay_body)
check("payment record shows the sender's real username and the amount on both sides", '"%player%", to.name' in pay_body and '"%player%", fa.name' in pay_body and "Money.fmt(amt)" in pay_body)
fx, pm = java["PaymentFx.java"], java["PaymentMenu.java"]
check("popup shows the RECEIVER's own head in the centre, a golden ring, rays, the amount and the sender", all(x in pm for x in ("Items.head(player", "CENTER = 22", "GOLD_BLOCK", "RAYS", "+$", "From: ")))
check("rarity title (LEGENDARY style): shimmering gold banner, rarity from the amount", "{\"6\", \"e\", \"f\", \"e\"}" in fx and "rarityFor(pay.amount)" in fx and any(r["name"] == "LEGENDARY" and r["color"] == "&6" for r in y["config.yml"]["payment-animation"]["rarities"]))
check("rarities are ascending and cover every amount from $1 (no gaps)", [r["min"] for r in y["config.yml"]["payment-animation"]["rarities"]] == sorted(r["min"] for r in y["config.yml"]["payment-animation"]["rarities"]) and y["config.yml"]["payment-animation"]["rarities"][0]["min"] <= 1)
check("glowing particles + golden rays in the action bar + sounds", all(x in fx for x in ("Particle.END_ROD", "Particle.DUST", "Particle.TOTEM_OF_UNDYING", "sendActionBar", "Sound.")))
check("one clean centred notification per payment: queued one after another, bursts merged (no list of payers)", "max-queue" in fx and "q.peekLast()" in fx and "pollFirst()" in fx and "ArrayList<Payer" not in fx)
check("gameplay is not obstructed: popup skipped in combat or when another menu is open, auto-closes, can be disabled per player (/payanim) or in config", "inCombat(p)" in fx and "InventoryType.CRAFTING" in fx and "p.closeInventory()" in fx and "animOff" in fx and y["config.yml"]["payment-animation"]["enabled"] is True)
check("Bedrock-safe: only chest menus, titles, action bar, particles and sounds (no custom entities, fonts, packs or Java-only features)", not re.search(r"ItemDisplay|TextDisplay|BlockDisplay|BossBar|ResourcePack|ArmorStand|sendPacket|ProtocolLib", fx + pm))
check("popup items cannot be taken (all menu clicks and drags are cancelled)", "extends Menu" in pm and "e.setCancelled(true)" in java["Listeners.java"])

print("== 2.0.0: Blocks / Redstone shop cleanup")
BLOCKCATS = {"stone", "woodblocks", "colored", "metal", "nether_end", "utility", "redstone"}
check("every Blocks/Redstone category skips items that /sell already handles (exclude-sold)", all(shop[c].get("exclude-sold") is True for c in BLOCKCATS))
check("the real /sell registry (market.yml) is read before the Blocks list is generated", "soldMaterials()" in java["ShopManager.java"] and "dedupe.contains(cat) && sold.contains(mat)" in java["ShopManager.java"] and "market.yml" in java["ShopManager.java"])
sold = {i["material"] for c in y["market.yml"]["categories"].values() for i in c["items"]}
explicit_in_blocks = {c: [i["material"] for i in shop[c]["items"] if "material" in i] for c in BLOCKCATS}
check("the only item hand-listed in a Blocks/Redstone category is Redstone Dust (explicitly requested; the one deliberate /sell overlap)", explicit_in_blocks["redstone"] == ["REDSTONE"] and all(not v for c, v in explicit_in_blocks.items() if c != "redstone"))
check("no 'item: true' rule feeds a Blocks/Redstone category, so no standalone items (diamond, iron ingot, redstone dust...) ever appear in them", not [r for r in bl["rules"] if r["category"] in BLOCKCATS and r.get("item")])
check("Blocks/Redstone rules never match non-block ingots/gems/dusts by name", not [n for n in ("DIAMOND", "IRON_INGOT", "GOLD_INGOT", "COPPER_INGOT", "EMERALD", "COAL", "LAPIS_LAZULI", "QUARTZ", "NETHERITE_INGOT", "REDSTONE") if rule_for(n) and rule_for(n)["category"] in BLOCKCATS])
dup_removed = sorted(n for n in U if n in sold and rule_for(n) and rule_for(n)["category"] in BLOCKCATS)
check(f"names that /sell buys and that WOULD have been in Blocks are removed ({len(dup_removed)}: {', '.join(dup_removed[:8])}...)", "COBBLESTONE" in dup_removed and "STONE" in dup_removed and len(dup_removed) >= 2)
BLOCK_NAMES = [n for n in sorted(U) if not any(x.match(n) for x in excl) and rule_for(n) and rule_for(n)["category"] in BLOCKCATS and n not in sold]
bp = [pack(n)[1] for n in BLOCK_NAMES]
check(f"Blocks/Redstone prices vary item by item ({len(set(bp))} distinct pack prices over {len(bp)} blocks, ${min(bp)} to ${max(bp):,}); not one flat price", len(set(bp)) >= 25 and max(bp) / max(1, min(bp)) >= 100)
unit = lambda n: rule_for(n)["unit"]
check("difficulty drives price: dirt < stone-tier < obsidian < gold block < diamond block < netherite block < beacon/dragon egg", unit("DIRT") < unit("COBBLESTONE") < unit("OBSIDIAN") < unit("GOLD_BLOCK") < unit("DIAMOND_BLOCK") < unit("NETHERITE_BLOCK") and unit("BEACON") > unit("DIAMOND_BLOCK") and unit("DRAGON_EGG") > unit("BEACON"))
check("rare structure/drop blocks cost far more than common ones (sculk catalyst, wither skull, heavy core vs planks/glass)", unit("SCULK_CATALYST") > 100 * unit("OAK_PLANKS") and unit("WITHER_SKELETON_SKULL") > unit("PLAYER_HEAD") > unit("OAK_PLANKS") * 100 and unit("HEAVY_CORE") > 1000)
check("storage blocks are included when not in /sell (coal, iron, gold, diamond, emerald, lapis, redstone, hay, bone, slime)", all(rule_for(n) is not None for n in ("COAL_BLOCK", "IRON_BLOCK", "GOLD_BLOCK", "DIAMOND_BLOCK", "EMERALD_BLOCK", "LAPIS_BLOCK", "REDSTONE_BLOCK", "HAY_BLOCK", "BONE_BLOCK", "SLIME_BLOCK")))
RS = "REDSTONE_TORCH REDSTONE_BLOCK REPEATER COMPARATOR OBSERVER PISTON STICKY_PISTON DISPENSER DROPPER HOPPER TARGET LEVER OAK_BUTTON STONE_BUTTON OAK_PRESSURE_PLATE STONE_PRESSURE_PLATE HEAVY_WEIGHTED_PRESSURE_PLATE LIGHT_WEIGHTED_PRESSURE_PLATE TRIPWIRE_HOOK DAYLIGHT_DETECTOR REDSTONE_LAMP NOTE_BLOCK CRAFTER RAIL POWERED_RAIL DETECTOR_RAIL ACTIVATOR_RAIL SLIME_BLOCK HONEY_BLOCK LIGHTNING_ROD COPPER_BULB WAXED_OXIDIZED_COPPER_BULB SCULK_SENSOR CALIBRATED_SCULK_SENSOR TRAPPED_CHEST".split()
check("the Redstone shop has every listed component (plus Redstone Dust hand-listed)", all(rule_for(n) and rule_for(n)["category"] == "redstone" for n in RS) and shop["redstone"]["items"][0]["material"] == "REDSTONE", str([n for n in RS if not rule_for(n) or rule_for(n)["category"] != "redstone"]))
ru = lambda n: rule_for(n)["unit"] * rule_for(n)["amount"] / rule_for(n)["amount"]
check("redstone prices follow ingredients/crafting steps: lever < torch < dropper < piston < sticky piston < hopper < crafter; repeater < comparator", ru("LEVER") < ru("REDSTONE_TORCH") < ru("DROPPER") < ru("PISTON") < ru("STICKY_PISTON") < ru("HOPPER") < ru("CRAFTER") and ru("REPEATER") < ru("COMPARATOR") and ru("DROPPER") < ru("DISPENSER") < ru("HOPPER") and ru("TRIPWIRE_HOOK") < ru("OBSERVER"))
check("redstone dust keeps the exact $35 per 16 in both places", ores["REDSTONE"] == (16, 35) and shop["redstone"]["items"][0] == {"material": "REDSTONE", "amount": 16, "price": 35})

print("== 2.0.0: Combat shop")
check("Trident is $350, a mid-tier weapon: above the iron sword, below the netherite sword", cp["TRIDENT"] == 350 and cp["IRON_SWORD"] < 350 < cp["NETHERITE_SWORD"])
check("ALL armor trim templates are free: one rule matches every *_ARMOR_TRIM_SMITHING_TEMPLATE at price 0", all(rule_for(n) and rule_for(n)["unit"] == 0 and rule_for(n)["category"] == "combat" and rule_for(n).get("item") for n in U if n.endswith("_ARMOR_TRIM_SMITHING_TEMPLATE")) and any(n.endswith("_ARMOR_TRIM_SMITHING_TEMPLATE") for n in U))
check("the netherite UPGRADE template is not caught by the free-trim rule", rule_for("NETHERITE_UPGRADE_SMITHING_TEMPLATE") is None or rule_for("NETHERITE_UPGRADE_SMITHING_TEMPLATE")["unit"] != 0)
sets_all = {i["name"] for i in combat if "set" in i}
check("Combat has armor sets for every tier (leather, chainmail, golden, iron, diamond, netherite) with 4 pieces each", all(any(n.startswith(f"Full {t}") for n in sets_all) for t in ("Leather", "Chainmail", "Golden", "Iron", "Diamond", "Netherite")) and all(len(i["set"]) == 4 for i in combat if "set" in i))
need = {"WOODEN_SWORD", "STONE_SWORD", "GOLDEN_SWORD", "IRON_SWORD", "DIAMOND_SWORD", "NETHERITE_SWORD", "BOW", "CROSSBOW", "ARROW", "SPECTRAL_ARROW", "SHIELD", "TRIDENT", "MACE", "TOTEM_OF_UNDYING", "TURTLE_HELMET", "WIND_CHARGE", "WOLF_ARMOR", "NETHERITE_SPEAR", "IRON_SPEAR"}
check("Combat has the weapons and gear players expect (swords, bows, arrows, trident, mace, spears, shield, totem, horse/wolf armor)", need <= set(cp), str(need - set(cp)))
check("existing combat prices were preserved except the requested trident change", cp["IRON_SWORD"] == 35 and cp["DIAMOND_SWORD"] == 450 and cp["NETHERITE_SWORD"] == 1400 and cp["BOW"] == 35 and cp["SHIELD"] == 40)

print("== 2.0.0: every category priced item by item")
for cat, n in (("ores", 8), ("combat", 15), ("tools", 15), ("food", 12), ("misc", 20)):
    pr = [i["price"] / i.get("amount", 1) for i in shop[cat]["items"] if "price" in i]
    check(f"{cat}: {len(set(round(x, 3) for x in pr))} different unit prices over {len(pr)} items (not one flat price)", len(set(round(x, 3) for x in pr)) >= n)
names = [i["material"] for c in shop.values() for i in c["items"] if "material" in i]
dups = sorted({m for m in names if names.count(m) > 1})
check("no item is listed twice in hand-written categories (only Redstone Dust, requested in both Ores and Redstone)", dups == ["REDSTONE"], str(dups))

print(f"\n{passed} passed, {failed} failed"); sys.exit(1 if failed else 0)
