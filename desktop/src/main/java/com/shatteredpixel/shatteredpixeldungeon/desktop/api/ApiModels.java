package com.shatteredpixel.shatteredpixeldungeon.desktop.api;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;

/** Wire-only DTOs. Never put game objects in these classes. Null optional fields are omitted. */
public final class ApiModels {
	public static final int VERSION = 1;

	private ApiModels() {}

	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
	@JsonSubTypes({
			@JsonSubTypes.Type(value = CellAction.class, name = "cell"),
			@JsonSubTypes.Type(value = WaitAction.class, name = "wait"),
			@JsonSubTypes.Type(value = SearchAction.class, name = "search"),
			@JsonSubTypes.Type(value = InspectAction.class, name = "inspect"),
			@JsonSubTypes.Type(value = ItemAction.class, name = "item"),
			@JsonSubTypes.Type(value = ButtonAction.class, name = "button"),
			@JsonSubTypes.Type(value = TextAction.class, name = "text"),
			@JsonSubTypes.Type(value = BackAction.class, name = "back"),
			@JsonSubTypes.Type(value = TalentAction.class, name = "talent")
	})
	@Schema(description = "One ordinary player input, using the latest observation's handles. "
			+ "Do not issue concurrent actions or blindly retry after a timeout.",
			discriminatorProperty = "type",
			oneOf = {CellAction.class, WaitAction.class, SearchAction.class, InspectAction.class,
					ItemAction.class, ButtonAction.class, TextAction.class, BackAction.class, TalentAction.class},
			discriminatorMapping = {
					@DiscriminatorMapping(value = "cell", schema = CellAction.class),
					@DiscriminatorMapping(value = "wait", schema = WaitAction.class),
					@DiscriminatorMapping(value = "search", schema = SearchAction.class),
					@DiscriminatorMapping(value = "inspect", schema = InspectAction.class),
					@DiscriminatorMapping(value = "item", schema = ItemAction.class),
					@DiscriminatorMapping(value = "button", schema = ButtonAction.class),
					@DiscriminatorMapping(value = "text", schema = TextAction.class),
					@DiscriminatorMapping(value = "back", schema = BackAction.class),
					@DiscriminatorMapping(value = "talent", schema = TalentAction.class)
			})
	public abstract static class Action {
		@Schema(description = "ID from the most recent observation.", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
		public Long observation_id;
	}

	@Schema(description = "Normal map click: walk, attack, interact, or choose a pending target. "
			+ "May walk multiple cells; choose a neighbor for a single step.")
	public static final class CellAction extends Action {
		@Schema(minimum = "0", example = "123", requiredMode = Schema.RequiredMode.REQUIRED)
		public Integer cell;
	}

	@Schema(description = "Wait once, not continuous resting.")
	public static final class WaitAction extends Action {}

	@Schema(description = "Deliberate search, with the normal time cost.")
	public static final class SearchAction extends Action {}

	@Schema(description = "Open the ordinary inspection UI for a currently visible cell.")
	public static final class InspectAction extends Action {
		@Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
		public Integer cell;
	}

	@Schema(description = "Execute an advertised inventory action. Modal UI must be handled first.")
	public static final class ItemAction extends Action {
		@Schema(example = "item-2", requiredMode = Schema.RequiredMode.REQUIRED)
		public String id;
		@Schema(description = "An exact value from the item's actions.", example = "ZAP", requiredMode = Schema.RequiredMode.REQUIRED)
		public String action;
	}

	@Schema(description = "Click an advertised enabled button on the current input surface.")
	public static final class ButtonAction extends Action {
		@Schema(example = "button-3", requiredMode = Schema.RequiredMode.REQUIRED)
		public String id;
	}

	@Schema(description = "Replace a visible dialog's text without submitting. Dialog-specific limits also apply.")
	public static final class TextAction extends Action {
		@Schema(maxLength = 200, requiredMode = Schema.RequiredMode.REQUIRED)
		public String text;
	}

	@Schema(description = "Dismiss a window or cancel inventory selection, targeting, or pathfinding.")
	public static final class BackAction extends Action {}

	@Schema(description = "Spend an available talent point using an advertised localized talent name.")
	public static final class TalentAction extends Action {
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
		public String name;
	}

	@Schema(description = "Player-visible observation. Gameplay fields are absent outside a loaded GameScene. "
			+ "Null optional fields are omitted, not emitted as JSON null. Names and descriptions are localized.")
	public static final class Observation {
		@Schema(allowableValues = {"1"}, requiredMode = Schema.RequiredMode.REQUIRED)
		public int api_version = VERSION;
		@Schema(description = "Every capture advances this ID and invalidates previous handles.", requiredMode = Schema.RequiredMode.REQUIRED)
		public long observation_id;
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
		public List<String> capabilities = new ArrayList<>();
		@Schema(description = "Current scene name; diagnostic, not a stable enum.", requiredMode = Schema.RequiredMode.REQUIRED)
		public String scene;
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
		public boolean modal;
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
		public UiState ui = new UiState();
		@Schema(allowableValues = {"menu", "ready", "targeting", "dead"}, requiredMode = Schema.RequiredMode.REQUIRED)
		public String status;
		@Schema(description = "Present while choosing a map target.")
		public String target_prompt;
		public Integer depth;
		@Schema(description = "Distinguishes main floors from subfloors.")
		public Integer branch;
		public EvaluationStats evaluation_stats;
		public List<Key> keys;
		@Schema(description = "Displayed boss-bar text, when present.")
		public String boss_health_text;
		public HeroState hero;
		public List<InventoryItem> inventory;
		public MapState map;
		public List<VisibleObject> objects;
		@Schema(description = "Visible rendered warning markers, not predictions from hidden AI.")
		public List<Integer> danger_cells;
		@Schema(description = "Only in cell-action responses; never replayed by GET /state.")
		public MovementReceipt movement_trace;
	}

	public static final class UiState {
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
		public List<String> texts = new ArrayList<>();
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
		public List<ButtonState> buttons = new ArrayList<>();
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
		public List<CheckboxState> checkboxes = new ArrayList<>();
		@Schema(description = "Editable value of the visible text-entry dialog.")
		public String text_input;
		@Schema(description = "Rendered alchemy energy text.")
		public String energy_text;
	}

	public static final class ButtonState {
		@Schema(description = "Observation-scoped handle.", requiredMode = Schema.RequiredMode.REQUIRED)
		public String id;
		@Schema(description = "Rendered text or hover label; may be empty.", requiredMode = Schema.RequiredMode.REQUIRED)
		public String label;
		public Boolean checked;
	}

	@Schema(requiredProperties = {"label", "checked", "enabled"})
	public static final class CheckboxState {
		public String label;
		public boolean checked;
		public boolean enabled;
	}

	@Schema(description = "Own-run accounting only; never individual actor schedules.",
			requiredProperties = {"experience", "experience_to_level", "elapsed_turns"})
	public static final class EvaluationStats {
		public int experience;
		public int experience_to_level;
		public double elapsed_turns;
	}

	@Schema(description = "Player-visible journal key, not a backpack item.",
			requiredProperties = {"name", "depth", "quantity"})
	public static final class Key {
		public String name;
		public int depth;
		public int quantity;
	}

	@Schema(requiredProperties = {"cell", "name", "hero_class", "subclass", "armor_ability",
			"hp", "max_hp", "level", "strength", "gold", "buffs", "talents"})
	public static final class HeroState {
		public int cell;
		public String name;
		public String hero_class;
		public String subclass;
		public String armor_ability;
		public int hp;
		public int max_hp;
		public int level;
		public int strength;
		public int gold;
		public List<BuffState> buffs = new ArrayList<>();
		public List<TalentState> talents = new ArrayList<>();
	}

	@Schema(requiredProperties = {"name", "description"})
	public static final class BuffState {
		public String name;
		public String description;
		@Schema(description = "Player-visible icon index in interfaces/buffs.png (7px cells) or large_buffs.png (16px cells).",
				minimum = "0", maximum = "126")
		public Integer icon_id;
		@Schema(description = "Optional game icon color transform: RGB multipliers followed by RGB additions; output = source * multiplier + addition. No expiry animation.")
		public float[] icon_tint;
	}

	@Schema(requiredProperties = {"name", "description", "tier", "points", "max_points", "available"})
	public static final class TalentState {
		public String name;
		public String description;
		public int tier;
		public int points;
		public int max_points;
		@Schema(description = "Unspent points in this tier.")
		public int available;
	}

	@Schema(description = "Knowledge-filtered inventory/inspection text. Never raw Java types or unknown upgrade levels.",
			requiredProperties = {"name", "description", "quantity", "identified", "upgradable", "curse_known", "known_cursed"})
	public static class ItemState {
		public String name;
		public String description;
		public int quantity;
		public String status;
		@Schema(description = "Player-visible item image index in the pinned game's ItemSpriteSheet; not a hidden item identity.")
		public int sprite_id;
		@Schema(description = "Knowledge-filtered base upgrade shown in the item title. Zero also covers unidentified upgrades, not proof of an unupgraded item.")
		public int visible_upgrade;
		public boolean identified;
		public boolean upgradable;
		public boolean curse_known;
		@Schema(description = "False means not known cursed, not guaranteed uncursed.")
		public boolean known_cursed;
	}

	@Schema(requiredProperties = {"name", "description", "quantity", "identified", "upgradable",
			"curse_known", "known_cursed", "id", "equipped", "actions"})
	public static final class InventoryItem extends ItemState {
		@Schema(description = "Observation-scoped handle.")
		public String id;
		public boolean equipped;
		public List<String> actions = new ArrayList<>();
	}

	@Schema(requiredProperties = {"width", "height", "cells"})
	public static final class MapState {
		public int width;
		public int height;
		@Schema(description = "Only observed or mapped cells. Unrevealed cells are absent.")
		public List<Tile> cells = new ArrayList<>();
	}

	@Schema(description = "x = cell % width, y = cell / width (integer division). "
			+ "Off-screen cells are remembered snapshots, not refreshed live state.",
			requiredProperties = {"cell", "terrain", "name", "visible", "mapped", "observed"})
	public static final class Tile {
		public int cell;
		@Schema(description = "Public levels/Terrain.java ID; secret doors/traps use their apparent terrain.")
		public int terrain;
		public String name;
		public boolean visible;
		public boolean mapped;
		@Schema(description = "Seen by this API at least once, as opposed to mapping alone.")
		public boolean observed;
		public String trap;
		public String plant;
		@Schema(description = "Visible gas/cloud inspection descriptions; omitted off-screen.")
		public List<String> effects;
	}

	@Schema(description = "Visible character or heap, intentionally without an underlying kind discriminator "
			+ "so disguised mimics remain disguised. No exact monster HP, AI state, or concealed loot.",
			requiredProperties = {"cell", "name", "description"})
	public static final class VisibleObject {
		public int cell;
		public String name;
		public String description;
		public List<String> status_icons;
		public Boolean sleeping;
		@Schema(description = "Rendered health-bar pixels: [filled_pixels, total_pixels].")
		public int[] health_bar;
		public ItemState top_item;
		public Boolean for_sale;
		public Integer price;
	}

	@Schema(description = "Actual hero positions, never a planned path or intermediate map/creature observations.",
			requiredProperties = {"depth", "branch", "same_floor", "truncated", "cells"})
	public static final class MovementReceipt {
		@Schema(description = "Starting floor.")
		public int depth;
		public int branch;
		public boolean same_floor;
		@Schema(description = "True after the 4096-position receipt limit.")
		public boolean truncated;
		public List<Integer> cells = new ArrayList<>();
	}

	public enum ErrorCode {
		UNAUTHORIZED, BROWSER_ORIGIN, NOT_FOUND, REQUEST_TOO_LARGE, INVALID_REQUEST,
		STALE_OBSERVATION, INVALID_ACTION, DECISION_TIMEOUT, INTERRUPTED, INTERNAL_ERROR
	}

	public static final class ErrorResponse {
		@Schema(allowableValues = {"1"}, requiredMode = Schema.RequiredMode.REQUIRED)
		public int api_version = VERSION;
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
		public ErrorDetail error;

		public ErrorResponse(ErrorCode code, String message, boolean actionMayHaveExecuted) {
			error = new ErrorDetail();
			error.code = code;
			error.message = message;
			error.action_may_have_executed = actionMayHaveExecuted;
		}
	}

	public static final class ErrorDetail {
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
		public ErrorCode code;
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
		public String message;
		@Schema(description = "Read /state before continuing; never automatically replay the action.",
				requiredMode = Schema.RequiredMode.REQUIRED)
		public boolean action_may_have_executed;
	}
}
