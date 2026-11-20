package com.shatteredpixel.shatteredpixeldungeon.desktop.api;

import com.shatteredpixel.shatteredpixeldungeon.desktop.api.ApiModels.*;
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
import java.util.List;

/** Explicit allow-list, never reflection/serialization of game objects. */
final class PlayerObservation {

	final Map<String, Item> items = new LinkedHashMap<>();
	final Map<String, Button> buttons = new LinkedHashMap<>();
	private final IdentityHashMap<Level, Map<Integer, Tile>> memories = new IdentityHashMap<>();
	private Hero lastHero;

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

	Observation capture(long observationId) {
		items.clear();
		buttons.clear();
		Observation result = new Observation();
		result.observation_id = observationId;
		result.capabilities.add("movement_trace");
		result.scene = Game.scene().getClass().getSimpleName();
		Gizmo root = inputRoot();
		result.modal = root instanceof Window;
		UiState ui = result.ui;
		ui.texts.addAll(ApiUI.texts(root));
		for (Button button : ApiUI.buttons(root)) {
			String id = "button-" + buttons.size();
			buttons.put(id, button);
			ButtonState control = new ButtonState();
			control.id = id;
			control.label = ApiUI.label(button);
			// The check mark is rendered UI, not private game/challenge state.
			if (button instanceof com.shatteredpixel.shatteredpixeldungeon.ui.CheckBox) {
				control.checked = ((com.shatteredpixel.shatteredpixeldungeon.ui.CheckBox) button).checked();
			}
			ui.buttons.add(control);
		}
		for (com.shatteredpixel.shatteredpixeldungeon.ui.CheckBox checkbox : ApiUI.checkboxes(root)) {
			CheckboxState control = new CheckboxState();
			control.label = ApiUI.label(checkbox);
			control.checked = checkbox.checked();
			control.enabled = checkbox.isActive();
			ui.checkboxes.add(control);
		}
		if (root instanceof com.shatteredpixel.shatteredpixeldungeon.windows.WndTextInput) {
			ui.text_input = ((com.shatteredpixel.shatteredpixeldungeon.windows.WndTextInput)root).apiText();
		}
		if (Game.scene() instanceof com.shatteredpixel.shatteredpixeldungeon.scenes.AlchemyScene) {
			ui.energy_text = ((com.shatteredpixel.shatteredpixeldungeon.scenes.AlchemyScene) Game.scene()).apiEnergyText();
		}
		if (!(Game.scene() instanceof GameScene) || Dungeon.hero == null || Dungeon.level == null) {
			result.status = "menu";
			return result;
		}
		Hero hero = Dungeon.hero;
		Level level = Dungeon.level;
		result.status = !hero.isAlive() ? "dead" : GameScene.interfaceBlockingHero() ? "menu"
				: GameScene.apiTargetPrompt() != null ? "targeting" : "ready";
		result.target_prompt = GameScene.apiTargetPrompt();
		result.depth = Dungeon.depth;
		result.branch = Dungeon.branch;
		result.evaluation_stats = evaluationStats(hero);
		result.keys = new ArrayList<>();
		for (Notes.KeyRecord record : Notes.getRecords(Notes.KeyRecord.class)) {
			Key key = new Key();
			key.name = record.title();
			key.depth = record.depth();
			key.quantity = record.quantity();
			result.keys.add(key);
		}
		result.boss_health_text = BossHealthBar.apiHealthText();
		HeroState player = new HeroState();
		player.cell = hero.pos;
		player.name = hero.name();
		player.hero_class = hero.heroClass.title();
		player.subclass = hero.subClass.title();
		player.armor_ability = hero.armorAbility == null ? "" : hero.armorAbility.name();
		player.hp = hero.HP;
		player.max_hp = hero.HT;
		player.level = hero.lvl;
		player.strength = hero.STR();
		player.gold = Dungeon.gold;
		for (Buff buff : hero.buffs()) {
			if (buff.icon() == BuffIndicator.NONE) continue;
			BuffState b = new BuffState();
			b.name = buff.name();
			b.description = buff.desc();
			player.buffs.add(b);
		}
		for (int tier = 0; tier < hero.talents.size(); tier++) {
			if (hero.lvl + 1 < Talent.tierLevelThresholds[tier+1]) continue;
			for (Talent talent : hero.talents.get(tier).keySet()) {
				TalentState entry = new TalentState();
				entry.name = talent.title();
				entry.description = talent.desc();
				entry.tier = tier+1;
				entry.points = hero.pointsInTalent(talent);
				entry.max_points = talent.maxPoints();
				entry.available = hero.talentPointsAvailable(tier+1);
				player.talents.add(entry);
			}
		}
		result.hero = player;
		result.inventory = new ArrayList<>();
		for (Item item : hero.belongings) {
			String id = "item-" + items.size();
			items.put(id, item);
			InventoryItem entry = item(item, new InventoryItem());
			entry.id = id;
			entry.equipped = item.isEquipped(hero);
			entry.actions.addAll(item.actions(hero));
			result.inventory.add(entry);
		}
		result.map = captureMap(level);
		captureObjects(result, level);
		return result;
	}

	/** Own-run accounting only: hero-panel XP and cumulative score time.
	 * No enemy schedules, hidden actors, or item information is exported.
	 */
	static EvaluationStats evaluationStats(Hero hero) {
		EvaluationStats stats = new EvaluationStats();
		stats.experience = hero.exp; // WndHero.StatsTab's exact displayed value.
		stats.experience_to_level = hero.maxExp();
		// fixTime transfers elapsed time into Statistics.duration. Their sum
		// avoids resetting accounting at saves/floor changes; no per-actor times.
		stats.elapsed_turns = Statistics.duration + Actor.now();
		return stats;
	}

	MapState captureMap(Level level) {
		Map<Integer, Tile> memory = memory(level);
		MapState map = new MapState();
		map.width = level.width();
		map.height = level.height();
		for (int cell = 0; cell < level.length(); cell++) {
			boolean visible = level.heroFOV[cell];
			if (visible) {
				Tile seen = terrain(level, cell, true);
				seen.observed = true;
				memory.put(cell, seen);
			} else if (level.mapped[cell] && !memory.containsKey(cell)) {
				// Bootstrap mapped saves once, never poll live off-screen terrain again.
				rememberMappedCell(cell);
			}
			Tile known = memory.get(cell);
			if (known == null) continue;
			// Copy the snapshot without retaining or serializing any game object.
			Tile tile = new Tile();
			tile.cell = known.cell;
			tile.terrain = known.terrain;
			tile.name = known.name;
			tile.trap = known.trap;
			tile.plant = known.plant;
			tile.observed = known.observed;
			tile.visible = visible;
			tile.mapped = level.mapped[cell];
			if (visible) {
				tile.effects = new ArrayList<>();
				for (Blob blob : level.blobs.values()) {
					if (blob.volume > 0 && blob.cur[cell] > 0 && blob.tileDesc() != null) {
						tile.effects.add(blob.tileDesc());
					}
				}
			}
			map.cells.add(tile);
		}
		return map;
	}

	private void captureObjects(Observation result, Level level) {
		// Use one list with no underlying actor/heap discriminator: a disguised
		// mimic must not be exposed as a monster merely because it is a Java Mob.
		ArrayList<VisibleObject> objects = new ArrayList<>();
		List<Integer> danger = new ArrayList<>();
		// Read rendered red target markers only. Never export their private timing
		// or a boss's underlying target list (e.g. Yog's pending laser rays).
		synchronized (com.shatteredpixel.shatteredpixeldungeon.effects.TargetedCell.cells) {
			for (com.shatteredpixel.shatteredpixeldungeon.effects.TargetedCell marker
					: com.shatteredpixel.shatteredpixeldungeon.effects.TargetedCell.cells.valueList()) {
				Gizmo owner = marker;
				while (owner.parent != null) owner = owner.parent;
				if (owner == Game.scene() && marker.exists && marker.parent != null && marker.isVisible()
						&& marker.pos >= 0 && marker.pos < level.length() && level.heroFOV[marker.pos]) {
					danger.add(marker.pos);
				}
			}
		}
		for (Mob mob : level.mobs) {
			if (!objectVisible(level, mob.pos, mob.sprite != null && mob.sprite.isVisible())) continue;
			VisibleObject entry = new VisibleObject();
			entry.cell = mob.pos;
			entry.name = mob.name();
			entry.description = mob.info(); // Same inspection text, including mimic disguises.
			// Only status icons shown by this visible actor's ordinary inspection
			// window. No private immunity/resistance, alignment or AI fields.
			entry.status_icons = inspectionStatusIcons(mob);
			if (mob.sprite.apiSleepIconVisible()) entry.sleeping = true;
			entry.health_bar = mob.sprite.apiHealthPixels();
			if (mob.sprite instanceof GooSprite) {
				for (int cell : ((GooSprite) mob.sprite).apiWarningCells()) {
					if (level.heroFOV[cell]) danger.add(cell);
				}
			}
			// No exact HP, combat stats, AI state, loot, or Java class identifiers.
			objects.add(entry);
		}
		for (Heap heap : level.heaps.valueList()) {
			if (!level.heroFOV[heap.pos] || heap.sprite == null || !heap.sprite.isVisible()) continue;
			VisibleObject entry = new VisibleObject();
			entry.cell = heap.pos;
			entry.name = heap.title();
			entry.description = heap.info(); // Crystal chest category is visible through its lid.
			// Never reveal contents of chests, tombs, remains, or concealed stacks.
			if ((heap.type == Heap.Type.HEAP || heap.type == Heap.Type.FOR_SALE) && heap.peek() != null) {
				entry.top_item = item(heap.peek(), new ItemState());
			}
			if (heap.type == Heap.Type.FOR_SALE && heap.peek() != null) {
				entry.for_sale = true;
				// Same price as the ordinary, player-accessible purchase window.
				entry.price = Shopkeeper.sellPrice(heap.peek());
			}
			objects.add(entry);
		}
		objects.sort(Comparator.comparingInt(o -> o.cell));
		result.objects = objects;
		result.danger_cells = danger;
	}

	static int apparentTerrain(int terrain) {
		if (terrain == Terrain.SECRET_DOOR) return Terrain.WALL;
		if (terrain == Terrain.SECRET_TRAP) return Terrain.EMPTY;
		return terrain;
	}

	// heroFOV includes the game's Mind Vision/awareness reveals, not just optical LOS.
	static List<String> inspectionStatusIcons(Mob mob) {
		List<String> icons = new ArrayList<>();
		if (!com.shatteredpixel.shatteredpixeldungeon.actors.Char.hasProp(
				mob, com.shatteredpixel.shatteredpixeldungeon.actors.Char.Property.OBJECT)) {
			for (Buff buff : mob.buffs()) {
				if (buff.icon() != BuffIndicator.NONE) icons.add(buff.name());
			}
		}
		return icons;
	}

	static boolean objectVisible(Level level, int cell, boolean spriteVisible) {
		return spriteVisible && level.heroFOV[cell];
	}

	private Map<Integer, Tile> memory(Level level) {
		if (lastHero != Dungeon.hero) { memories.clear(); lastHero = Dungeon.hero; }
		return memories.computeIfAbsent(level, ignored -> new LinkedHashMap<>());
	}

	void rememberMappedCell(int cell) {
		Level level = Dungeon.level;
		Map<Integer, Tile> memory = memory(level);
		Tile previous = memory.get(cell);
		Tile mapped = terrain(level, cell, false);
		mapped.observed = previous != null && previous.observed;
		if (previous != null) mapped.plant = previous.plant;
		memory.put(cell, mapped);
	}

	private Tile terrain(Level level, int cell, boolean visible) {
		int terrain = apparentTerrain(level.map[cell]);
		Tile tile = new Tile();
		tile.cell = cell;
		tile.terrain = terrain;
		// Includes player-visible custom landmarks such as the distant well.
		tile.name = WndInfoCell.cellName(cell);
		if (level.traps.get(cell) != null && level.traps.get(cell).visible) {
			tile.trap = level.traps.get(cell).name();
		}
		if (visible && level.plants.get(cell) != null) tile.plant = level.plants.get(cell).name();
		return tile;
	}

	private <T extends ItemState> T item(Item item, T value) {
		value.name = item.title();
		value.description = item.info();
		value.quantity = item.quantity();
		value.status = item.status();
		value.identified = item.isIdentified();
		value.upgradable = item.isUpgradable();
		value.curse_known = item.cursedKnown;
		value.known_cursed = knownCursed(item);
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
