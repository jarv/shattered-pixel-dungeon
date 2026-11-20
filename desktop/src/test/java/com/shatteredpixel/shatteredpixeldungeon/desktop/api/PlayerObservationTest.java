package com.shatteredpixel.shatteredpixeldungeon.desktop.api;

import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.ui.ApiUI;
import com.watabou.noosa.Group;
import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.JsonReader;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.MindVision;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PlayerObservationTest {
	private Level previousLevel;
	private Hero previousHero;
	private Level level;

	@Before public void setupLevel() {
		previousLevel = Dungeon.level;
		previousHero = Dungeon.hero;
		level = new Level() {
			@Override protected boolean build() { return true; }
			@Override protected void createMobs() {}
			@Override protected void createItems() {}
			@Override public String tileName(int terrain) { return "tile-" + terrain; }
		};
		level.setSize(15, 15);
		level.customTiles = new java.util.ArrayList<>();
		level.customTerrain = new java.util.ArrayList<>();
		level.mobs = new java.util.HashSet<>();
		level.heaps = new com.watabou.utils.SparseArray<>();
		level.traps = new com.watabou.utils.SparseArray<>();
		level.plants = new com.watabou.utils.SparseArray<>();
		level.blobs = new java.util.HashMap<>();
		level.discoverable = new boolean[level.length()];
		java.util.Arrays.fill(level.discoverable, true);
		Dungeon.level = level;
		Dungeon.hero = new Hero();
		Dungeon.hero.pos = 32;
	}

	@After public void restoreLevel() {
		Dungeon.level = previousLevel;
		Dungeon.hero = previousHero;
	}

	private JsonValue tile(PlayerObservation observation, int cell) {
		for (JsonValue tile : json(observation.captureMap(level)).get("cells")) {
			if (tile.getInt("cell") == cell) return tile;
		}
		return null;
	}

	private static JsonValue json(Object value) {
		try {
			if (value instanceof ApiModels.MapState) ApiContractTest.assertConforms("MapState", value);
			if (value instanceof ApiModels.EvaluationStats) ApiContractTest.assertConforms("EvaluationStats", value);
			return new JsonReader().parse(ApiJson.MAPPER.writeValueAsString(value));
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
	}

	@Test public void evaluationStatsContainOnlyOwnExperienceAndElapsedRunTime() {
		Dungeon.hero.exp = 3;
		JsonValue stats = json(PlayerObservation.evaluationStats(Dungeon.hero));
		assertEquals(3, stats.getInt("experience"));
		assertEquals(Dungeon.hero.maxExp(), stats.getInt("experience_to_level"));
		assertEquals(true, stats.has("elapsed_turns"));
		assertEquals(3, stats.size);
	}

	@Test public void mappingExportsUnseenTerrainWithoutMakingItVisible() {
		PlayerObservation observation = new PlayerObservation();
		assertEquals(null, tile(observation, 100));
		level.mapped[100] = true;
		level.map[100] = Terrain.EXIT;
		JsonValue mapped = tile(observation, 100);
		assertEquals(Terrain.EXIT, mapped.getInt("terrain"));
		assertEquals(true, mapped.getBoolean("mapped"));
		assertEquals(false, mapped.getBoolean("visible"));
		assertEquals(false, mapped.getBoolean("observed"));
		assertEquals(false, mapped.has("effects"));
		assertEquals(false, PlayerObservation.objectVisible(level, 100, true));
	}

	@Test public void mappedTerrainStaysASnapshotUntilRevealedAgain() {
		PlayerObservation observation = new PlayerObservation();
		level.mapped[100] = true;
		level.map[100] = Terrain.WALL;
		assertEquals(Terrain.WALL, tile(observation, 100).getInt("terrain"));
		level.map[100] = Terrain.DOOR;
		assertEquals(Terrain.WALL, tile(observation, 100).getInt("terrain"));
		observation.rememberMappedCell(100);
		assertEquals(Terrain.DOOR, tile(observation, 100).getInt("terrain"));
		level.heroFOV[100] = true;
		assertEquals(true, tile(observation, 100).getBoolean("observed"));
		level.heroFOV[100] = false;
		level.map[100] = Terrain.EMPTY;
		assertEquals(Terrain.DOOR, tile(observation, 100).getInt("terrain"));
	}

	@Test public void mindVisionExportsDistantCreatureLocationsUntilItExpires() {
		Rat rat = new Rat();
		rat.pos = 192;
		level.mobs.add(rat);
		level.updateFieldOfView(Dungeon.hero, level.heroFOV);
		assertEquals(false, PlayerObservation.objectVisible(level, rat.pos, true));
		MindVision vision = Buff.affect(Dungeon.hero, MindVision.class);
		level.updateFieldOfView(Dungeon.hero, level.heroFOV);
		assertEquals(true, Dungeon.hero.mindVisionEnemies.contains(rat));
		assertEquals(true, PlayerObservation.objectVisible(level, rat.pos, true));
		assertEquals(false, PlayerObservation.objectVisible(level, rat.pos, false));
		vision.detach();
		level.updateFieldOfView(Dungeon.hero, level.heroFOV);
		assertEquals(false, PlayerObservation.objectVisible(level, rat.pos, true));
	}

	@Test public void mappingEventsSnapshotDiscoveredTrapsButNotUnseenPlants() {
		PlayerObservation observation = new PlayerObservation();
		level.mapped[100] = true;
		level.map[100] = Terrain.TRAP;
		com.shatteredpixel.shatteredpixeldungeon.levels.traps.BurningTrap trap =
				new com.shatteredpixel.shatteredpixeldungeon.levels.traps.BurningTrap() {
					@Override public String name() { return "burning trap"; }
				};
		trap.visible = true;
		level.traps.put(100, trap);
		level.plants.put(100, new com.shatteredpixel.shatteredpixeldungeon.plants.Sungrass());
		java.util.function.IntConsumer previousObserver = ApiUI.mappedCellObserver;
		try {
			ApiUI.mappedCellObserver = observation::rememberMappedCell;
			ApiUI.mappedCell(100);
			level.map[100] = Terrain.EMPTY;
			level.traps.clear();
			JsonValue mapped = tile(observation, 100);
			assertEquals(Terrain.TRAP, mapped.getInt("terrain"));
			assertEquals("burning trap", mapped.getString("trap"));
			assertEquals(false, mapped.has("plant"));
		} finally {
			ApiUI.mappedCellObserver = previousObserver;
		}
	}
	@Test public void hiddenCursesAreNotExported() {
		Item item = new Item();
		item.cursed = true;
		item.cursedKnown = false;
		assertEquals(false, PlayerObservation.knownCursed(item));
		item.cursedKnown = true;
		assertEquals(true, PlayerObservation.knownCursed(item));
		item.cursed = false;
		assertEquals(false, PlayerObservation.knownCursed(item));
	}
	@Test public void visibleActorInspectionExportsOnlyRenderedStatusIconNames() {
		Rat rat = new Rat();
		Buff visible = new Buff() {
			@Override public int icon() { return com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator.CORRUPT; }
			@Override public String name() { return "Corruption"; }
		};
		Buff hidden = new Buff() {
			@Override public int icon() { return com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator.NONE; }
			@Override public String name() { return "private tracker"; }
		};
		visible.attachTo(rat);
		hidden.attachTo(rat);
		JsonValue icons = json(PlayerObservation.inspectionStatusIcons(rat));
		assertEquals(1, icons.size);
		assertEquals("Corruption", icons.getString(0));
	}
	@Test public void secretsHaveOnlyTheirVisibleAppearance() {
		assertEquals(Terrain.WALL, PlayerObservation.apparentTerrain(Terrain.SECRET_DOOR));
		assertEquals(Terrain.EMPTY, PlayerObservation.apparentTerrain(Terrain.SECRET_TRAP));
	}

	@Test public void discoveredTerrainIsNotMasked() {
		assertEquals(Terrain.DOOR, PlayerObservation.apparentTerrain(Terrain.DOOR));
		assertEquals(Terrain.TRAP, PlayerObservation.apparentTerrain(Terrain.TRAP));
		assertEquals(Terrain.HIGH_GRASS, PlayerObservation.apparentTerrain(Terrain.HIGH_GRASS));
	}

	@Test public void uiInspectionIgnoresErasedChildren() {
		Group group = new Group() {{ members.add(null); length++; }};
		assertEquals(0, ApiUI.texts(group).size());
		assertEquals(0, ApiUI.buttons(group).size());
		assertEquals(0, ApiUI.checkboxes(group).size());
	}

	@Test public void uiInspectionAcceptsNoScene() {
		assertEquals(0, ApiUI.texts(null).size());
		assertEquals(0, ApiUI.buttons(null).size());
		assertEquals(0, ApiUI.checkboxes(null).size());
	}
}
