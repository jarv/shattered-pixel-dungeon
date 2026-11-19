package com.shatteredpixel.shatteredpixeldungeon.desktop.api;

import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonWriter;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Shopkeeper;
import com.shatteredpixel.shatteredpixeldungeon.journal.Notes;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.ApiUI;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.shatteredpixel.shatteredpixeldungeon.ui.Button;
import com.shatteredpixel.shatteredpixeldungeon.ui.BossHealthBar;
import com.shatteredpixel.shatteredpixeldungeon.sprites.GooSprite;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndInfoCell;
import com.watabou.noosa.Game;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Group;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;

/** Explicit allow-list, never reflection/serialization of game objects. */
final class PlayerObservation {

	final Map<String, Item> items = new LinkedHashMap<>();
	final Map<String, Button> buttons = new LinkedHashMap<>();
	private final IdentityHashMap<Level, Map<Integer, JsonValue>> memories = new IdentityHashMap<>();
	private Hero lastHero;

	static JsonValue object() { return new JsonValue(JsonValue.ValueType.object); }
	static JsonValue array() { return new JsonValue(JsonValue.ValueType.array); }
	static void put(JsonValue o, String key, String value) {
		o.addChild(key, value == null ? new JsonValue(JsonValue.ValueType.nullValue) : new JsonValue(value));
	}
	static void put(JsonValue o, String key, long value) { o.addChild(key, new JsonValue(value)); }
	static void put(JsonValue o, String key, boolean value) { o.addChild(key, new JsonValue(value)); }

	static Gizmo inputRoot() {
		Group scene = Game.scene();
		if (scene == null) return null;
		Gizmo[] children = scene.membersSnapshot();
		for (int i = children.length - 1; i >= 0; i--) {
			if (children[i] instanceof Window && children[i].exists && children[i].isVisible()) {
				return children[i];
			}
		}
		if (scene instanceof GameScene && GameScene.apiInventorySelectionPane() != null) {
			return GameScene.apiInventorySelectionPane();
		}
		return scene;
	}

	JsonValue capture(long observationId) {
		items.clear();
		buttons.clear();
		JsonValue result = object();
		put(result, "observation_id", observationId);
		JsonValue capabilities = array();
		capabilities.addChild(new JsonValue("movement_trace"));
		result.addChild("capabilities", capabilities);
		put(result, "scene", Game.scene().getClass().getSimpleName());
		Gizmo root = inputRoot();
		put(result, "modal", root instanceof Window);
		JsonValue ui = object();
		JsonValue texts = array();
		for (String text : ApiUI.texts(root)) texts.addChild(new JsonValue(text));
		ui.addChild("texts", texts);
		JsonValue controls = array();
		for (Button button : ApiUI.buttons(root)) {
			String id = "button-" + buttons.size();
			buttons.put(id, button);
			JsonValue control = object();
			put(control, "id", id);
			put(control, "label", ApiUI.label(button));
			// The check mark is rendered UI, not private game/challenge state.
			if (button instanceof com.shatteredpixel.shatteredpixeldungeon.ui.CheckBox) {
				put(control, "checked", ((com.shatteredpixel.shatteredpixeldungeon.ui.CheckBox) button).checked());
			}
			controls.addChild(control);
		}
		ui.addChild("buttons", controls);
		JsonValue checkboxes = array();
		for (com.shatteredpixel.shatteredpixeldungeon.ui.CheckBox checkbox : ApiUI.checkboxes(root)) {
			JsonValue control = object();
			put(control, "label", ApiUI.label(checkbox));
			put(control, "checked", checkbox.checked());
			put(control, "enabled", checkbox.isActive());
			checkboxes.addChild(control);
		}
		ui.addChild("checkboxes", checkboxes);
		if (root instanceof com.shatteredpixel.shatteredpixeldungeon.windows.WndTextInput) {
			put(ui, "text_input", ((com.shatteredpixel.shatteredpixeldungeon.windows.WndTextInput)root).apiText());
		}
		if (Game.scene() instanceof com.shatteredpixel.shatteredpixeldungeon.scenes.AlchemyScene) {
			put(ui, "energy_text", ((com.shatteredpixel.shatteredpixeldungeon.scenes.AlchemyScene) Game.scene()).apiEnergyText());
		}
		result.addChild("ui", ui);
		if (!(Game.scene() instanceof GameScene) || Dungeon.hero == null || Dungeon.level == null) {
			put(result, "status", "menu");
			return result;
		}
		Hero hero = Dungeon.hero;
		Level level = Dungeon.level;
		put(result, "status", !hero.isAlive() ? "dead" : GameScene.interfaceBlockingHero() ? "menu"
				: GameScene.apiTargetPrompt() != null ? "targeting" : "ready");
		put(result, "target_prompt", GameScene.apiTargetPrompt());
		put(result, "depth", Dungeon.depth);
		put(result, "branch", Dungeon.branch);
		result.addChild("evaluation_stats", evaluationStats(hero));
		JsonValue keys = array();
		for (Notes.KeyRecord record : Notes.getRecords(Notes.KeyRecord.class)) {
			JsonValue key = object();
			put(key, "name", record.title());
			put(key, "depth", record.depth());
			put(key, "quantity", record.quantity());
			keys.addChild(key);
		}
		result.addChild("keys", keys);
		put(result, "boss_health_text", BossHealthBar.apiHealthText());
		JsonValue player = object();
		put(player, "cell", hero.pos);
		put(player, "name", hero.name());
		put(player, "hero_class", hero.heroClass.title());
		put(player, "subclass", hero.subClass.title());
		put(player, "armor_ability", hero.armorAbility == null ? "" : hero.armorAbility.name());
		put(player, "hp", hero.HP);
		put(player, "max_hp", hero.HT);
		put(player, "level", hero.lvl);
		put(player, "strength", hero.STR());
		put(player, "gold", Dungeon.gold);
		JsonValue buffs = array();
		for (Buff buff : hero.buffs()) {
			if (buff.icon() == BuffIndicator.NONE) continue;
			JsonValue b = object();
			put(b, "name", buff.name());
			put(b, "description", buff.desc());
			buffs.addChild(b);
		}
		player.addChild("buffs", buffs);
		JsonValue talents = array();
		for (int tier = 0; tier < hero.talents.size(); tier++) {
			if (hero.lvl + 1 < Talent.tierLevelThresholds[tier+1]) continue;
			for (Talent talent : hero.talents.get(tier).keySet()) {
				JsonValue entry = object();
				put(entry, "name", talent.title());
				put(entry, "description", talent.desc());
				put(entry, "tier", tier+1);
				put(entry, "points", hero.pointsInTalent(talent));
				put(entry, "max_points", talent.maxPoints());
				put(entry, "available", hero.talentPointsAvailable(tier+1));
				talents.addChild(entry);
			}
		}
		player.addChild("talents", talents);
		result.addChild("hero", player);
		JsonValue inventory = array();
		for (Item item : hero.belongings) {
			String id = "item-" + items.size();
			items.put(id, item);
			JsonValue entry = item(item);
			put(entry, "id", id);
			put(entry, "equipped", item.isEquipped(hero));
			JsonValue actions = array();
			for (String action : item.actions(hero)) actions.addChild(new JsonValue(action));
			entry.addChild("actions", actions);
			inventory.addChild(entry);
		}
		result.addChild("inventory", inventory);
		result.addChild("map", captureMap(level));
		captureObjects(result, level);
		return result;
	}

	/** Own-run accounting only: hero-panel XP and cumulative score time.
	 * No enemy schedules, hidden actors, or item information is exported.
	 */
	static JsonValue evaluationStats(Hero hero) {
		JsonValue stats = object();
		put(stats, "experience", hero.exp); // WndHero.StatsTab's exact displayed value.
		put(stats, "experience_to_level", hero.maxExp());
		// fixTime transfers elapsed time into Statistics.duration. Their sum
		// avoids resetting accounting at saves/floor changes; no per-actor times.
		stats.addChild("elapsed_turns", new JsonValue((double) (Statistics.duration + Actor.now())));
		return stats;
	}

	JsonValue captureMap(Level level) {
		Map<Integer, JsonValue> memory = memory(level);
		JsonValue map = object();
		put(map, "width", level.width());
		put(map, "height", level.height());
		JsonValue cells = array();
		for (int cell = 0; cell < level.length(); cell++) {
			boolean visible = level.heroFOV[cell];
			if (visible) {
				JsonValue seen = terrain(level, cell, true);
				put(seen, "observed", true);
				memory.put(cell, seen);
			} else if (level.mapped[cell] && !memory.containsKey(cell)) {
				// Bootstrap mapped saves once, never poll live off-screen terrain again.
				rememberMappedCell(cell);
			}
			JsonValue known = memory.get(cell);
			if (known == null) continue;
			JsonValue tile = new JsonReader().parse(known.toJson(JsonWriter.OutputType.json));
			put(tile, "visible", visible);
			put(tile, "mapped", level.mapped[cell]);
			if (visible) {
				JsonValue effects = array();
				for (Blob blob : level.blobs.values()) {
					if (blob.volume > 0 && blob.cur[cell] > 0 && blob.tileDesc() != null) {
						effects.addChild(new JsonValue(blob.tileDesc()));
					}
				}
				tile.addChild("effects", effects);
			}
			cells.addChild(tile);
		}
		map.addChild("cells", cells);
		return map;
	}

	private void captureObjects(JsonValue result, Level level) {
		// Use one list with no underlying actor/heap discriminator: a disguised
		// mimic must not be exposed as a monster merely because it is a Java Mob.
		ArrayList<JsonValue> objects = new ArrayList<>();
		JsonValue danger = array();
		// Read rendered red target markers only. Never export their private timing
		// or a boss's underlying target list (e.g. Yog's pending laser rays).
		synchronized (com.shatteredpixel.shatteredpixeldungeon.effects.TargetedCell.cells) {
			for (com.shatteredpixel.shatteredpixeldungeon.effects.TargetedCell marker
					: com.shatteredpixel.shatteredpixeldungeon.effects.TargetedCell.cells.valueList()) {
				Gizmo owner = marker;
				while (owner.parent != null) owner = owner.parent;
				if (owner == Game.scene() && marker.exists && marker.parent != null && marker.isVisible()
						&& marker.pos >= 0 && marker.pos < level.length() && level.heroFOV[marker.pos]) {
					danger.addChild(new JsonValue((long)marker.pos));
				}
			}
		}
		for (Mob mob : level.mobs) {
			if (!objectVisible(level, mob.pos, mob.sprite != null && mob.sprite.isVisible())) continue;
			JsonValue entry = object();
			put(entry, "cell", mob.pos);
			put(entry, "name", mob.name());
			put(entry, "description", mob.info()); // Same inspection text, including mimic disguises.
			// Only status icons shown by this visible actor's ordinary inspection
			// window. No private immunity/resistance, alignment or AI fields.
			entry.addChild("status_icons", inspectionStatusIcons(mob));
			if (mob.sprite.apiSleepIconVisible()) put(entry, "sleeping", true);
			int[] bar = mob.sprite.apiHealthPixels();
			if (bar != null) {
				JsonValue pixels = array();
				for (int value : bar) pixels.addChild(new JsonValue((long)value));
				entry.addChild("health_bar", pixels);
			}
			if (mob.sprite instanceof GooSprite) {
				for (int cell : ((GooSprite) mob.sprite).apiWarningCells()) {
					if (level.heroFOV[cell]) danger.addChild(new JsonValue((long)cell));
				}
			}
			// No exact HP, combat stats, AI state, loot, or Java class identifiers.
			objects.add(entry);
		}
		for (Heap heap : level.heaps.valueList()) {
			if (!level.heroFOV[heap.pos] || heap.sprite == null || !heap.sprite.isVisible()) continue;
			JsonValue entry = object();
			put(entry, "cell", heap.pos);
			put(entry, "name", heap.title());
			put(entry, "description", heap.info()); // Crystal chest category is visible through its lid.
			// Never reveal contents of chests, tombs, remains, or concealed stacks.
			if ((heap.type == Heap.Type.HEAP || heap.type == Heap.Type.FOR_SALE) && heap.peek() != null) {
				entry.addChild("top_item", item(heap.peek()));
			}
			if (heap.type == Heap.Type.FOR_SALE && heap.peek() != null) {
				put(entry, "for_sale", true);
				// Same price as the ordinary, player-accessible purchase window.
				put(entry, "price", Shopkeeper.sellPrice(heap.peek()));
			}
			objects.add(entry);
		}
		objects.sort(Comparator.comparingInt(o -> o.getInt("cell")));
		JsonValue visibleObjects = array();
		for (JsonValue entry : objects) visibleObjects.addChild(entry);
		result.addChild("objects", visibleObjects);
		result.addChild("danger_cells", danger);
	}

	static int apparentTerrain(int terrain) {
		if (terrain == Terrain.SECRET_DOOR) return Terrain.WALL;
		if (terrain == Terrain.SECRET_TRAP) return Terrain.EMPTY;
		return terrain;
	}

	// heroFOV includes the game's Mind Vision/awareness reveals, not just optical LOS.
	static JsonValue inspectionStatusIcons(Mob mob) {
		JsonValue icons = array();
		if (!com.shatteredpixel.shatteredpixeldungeon.actors.Char.hasProp(
				mob, com.shatteredpixel.shatteredpixeldungeon.actors.Char.Property.OBJECT)) {
			for (Buff buff : mob.buffs()) {
				if (buff.icon() != BuffIndicator.NONE) icons.addChild(new JsonValue(buff.name()));
			}
		}
		return icons;
	}

	static boolean objectVisible(Level level, int cell, boolean spriteVisible) {
		return spriteVisible && level.heroFOV[cell];
	}

	private Map<Integer, JsonValue> memory(Level level) {
		if (lastHero != Dungeon.hero) { memories.clear(); lastHero = Dungeon.hero; }
		return memories.computeIfAbsent(level, ignored -> new LinkedHashMap<>());
	}

	void rememberMappedCell(int cell) {
		Level level = Dungeon.level;
		Map<Integer, JsonValue> memory = memory(level);
		JsonValue previous = memory.get(cell);
		JsonValue mapped = terrain(level, cell, false);
		put(mapped, "observed", previous != null && previous.getBoolean("observed", false));
		if (previous != null && previous.has("plant")) put(mapped, "plant", previous.getString("plant"));
		memory.put(cell, mapped);
	}

	private JsonValue terrain(Level level, int cell, boolean visible) {
		int terrain = apparentTerrain(level.map[cell]);
		JsonValue tile = object();
		put(tile, "cell", cell);
		put(tile, "terrain", terrain);
		// Includes player-visible custom landmarks such as the distant well.
		put(tile, "name", WndInfoCell.cellName(cell));
		if (level.traps.get(cell) != null && level.traps.get(cell).visible) {
			put(tile, "trap", level.traps.get(cell).name());
		}
		if (visible && level.plants.get(cell) != null) put(tile, "plant", level.plants.get(cell).name());
		return tile;
	}

	private JsonValue item(Item item) {
		JsonValue value = object();
		put(value, "name", item.title());
		put(value, "description", item.info());
		put(value, "quantity", item.quantity());
		put(value, "status", item.status());
		put(value, "identified", item.isIdentified());
		put(value, "upgradable", item.isUpgradable());
		put(value, "curse_known", item.cursedKnown);
		put(value, "known_cursed", knownCursed(item));
		// title/info/status are the same knowledge-filtered strings used by inventory UI.
		// In particular, do not export item.getClass(), raw level, or unknown curse state.
		return value;
	}

	static boolean knownCursed(Item item) {
		return item.cursedKnown && (item.cursed
				|| item instanceof Weapon && ((Weapon) item).hasCurseEnchant()
				|| item instanceof Armor && ((Armor) item).hasCurseGlyph());
	}
}
