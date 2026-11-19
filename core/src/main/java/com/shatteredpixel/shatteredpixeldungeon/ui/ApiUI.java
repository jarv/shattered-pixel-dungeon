package com.shatteredpixel.shatteredpixeldungeon.ui;

import com.watabou.noosa.BitmapText;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Group;

import java.util.ArrayList;
import java.util.List;

/** Reads rendered labels and invokes ordinary button handlers, on the render thread only. */
public final class ApiUI {

	/** Optional mapping-event observer; receives only cells explicitly revealed by an item. */
	public static java.util.function.IntConsumer mappedCellObserver;
	/** Optional observer of the hero's publicly visible movement, not a pathfinding hook. */
	public static volatile java.util.function.IntConsumer heroMoveObserver;

	public static void heroMoved(int cell) {
		java.util.function.IntConsumer observer = heroMoveObserver;
		if (observer != null) observer.accept(cell);
	}

	public static void mappedCell(int cell) {
		if (mappedCellObserver != null) mappedCellObserver.accept(cell);
	}

	private ApiUI() {}

	public static List<String> texts(Gizmo root) {
		ArrayList<String> result = new ArrayList<>();
		collectText(root, result);
		return result;
	}

	private static void collectText(Gizmo node, List<String> result) {
		if (node == null || !node.exists || !node.isVisible()) return;
		String text = null;
		if (node instanceof RenderedTextBlock) {
			text = ((RenderedTextBlock) node).text();
		} else if (node instanceof BitmapText) {
			text = ((BitmapText) node).text();
		}
		if (text != null && !text.isEmpty()) result.add(text);
		// RenderedTextBlock's children repeat its text, one word at a time.
		if (node instanceof Group && !(node instanceof RenderedTextBlock)) {
			for (Gizmo child : ((Group) node).membersSnapshot()) collectText(child, result);
		}
	}

	public static List<Button> buttons(Gizmo root) {
		ArrayList<Button> result = new ArrayList<>();
		collectButtons(root, result);
		return result;
	}

	/** Rendered check marks remain observable when their controls are read-only. */
	public static List<CheckBox> checkboxes(Gizmo root) {
		ArrayList<CheckBox> result = new ArrayList<>();
		collectCheckboxes(root, result);
		return result;
	}

	private static void collectCheckboxes(Gizmo node, List<CheckBox> result) {
		if (node == null || !node.exists || !node.isVisible()) return;
		if (node instanceof CheckBox) result.add((CheckBox) node);
		if (node instanceof Group) {
			for (Gizmo child : ((Group) node).membersSnapshot()) collectCheckboxes(child, result);
		}
	}

	private static void collectButtons(Gizmo node, List<Button> result) {
		if (node == null || !node.exists || !node.isVisible() || !node.isActive()) return;
		if (node instanceof Button) result.add((Button) node);
		if (node instanceof Group) {
			for (Gizmo child : ((Group) node).membersSnapshot()) collectButtons(child, result);
		}
	}

	public static String label(Button button) {
		// Empty alchemy slots render a placeholder icon and have an Add hover label.
		if (button instanceof ItemSlot && ((ItemSlot) button).item instanceof com.shatteredpixel.shatteredpixeldungeon.windows.WndBag.Placeholder) {
			// Name the visibly distinct equipment placeholder icons, not private
			// Rose fields. Clicking an occupied slot removes gear, so these must
			// remain distinguishable from actual item titles.
			int image = ((ItemSlot) button).item.image;
			if (image == com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet.WEAPON_HOLDER) return "Empty weapon slot";
			if (image == com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet.ARMOR_HOLDER) return "Empty armor slot";
			String hover = button.hoverText();
			if (hover != null && !hover.isEmpty()) return hover;
		}
		if (button instanceof ItemSlot && ((ItemSlot) button).item != null) {
			return ((ItemSlot) button).item.title();
		}
		List<String> text = texts(button);
		if (!text.isEmpty()) return String.join(" ", text);
		String hover = button.hoverText();
		return hover == null ? "" : hover;
	}

	public static void click(Button button) {
		if (!button.exists || !button.isVisible() || !button.isActive()) {
			throw new IllegalArgumentException("Button is no longer available");
		}
		button.onPointerDown();
		button.onPointerUp();
		button.onClick();
	}
}
