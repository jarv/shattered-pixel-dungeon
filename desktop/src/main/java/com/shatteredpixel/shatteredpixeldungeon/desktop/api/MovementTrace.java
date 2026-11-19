package com.shatteredpixel.shatteredpixeldungeon.desktop.api;

import com.badlogic.gdx.utils.JsonValue;
import java.util.ArrayList;

/** Actual public hero positions during one ordinary cell input; never a planned path. */
final class MovementTrace {
	private final ArrayList<Integer> cells = new ArrayList<>();
	private boolean active;
	private boolean truncated;
	private int depth;
	private int branch;

	synchronized void begin(int depth, int branch, int cell) {
		reset();
		active = true;
		this.depth = depth;
		this.branch = branch;
		moved(cell);
	}

	synchronized void moved(int cell) {
		if (!active || (!cells.isEmpty() && cells.get(cells.size()-1) == cell)) return;
		if (cells.size() >= 4096) {
			truncated = true;
			return;
		}
		cells.add(cell);
	}

	synchronized JsonValue finish(int endDepth, int endBranch, int cell) {
		if (!active) return null;
		boolean same = endDepth == depth && endBranch == branch;
		if (same) moved(cell);
		JsonValue result = PlayerObservation.object();
		PlayerObservation.put(result, "depth", depth);
		PlayerObservation.put(result, "branch", branch);
		PlayerObservation.put(result, "same_floor", same);
		PlayerObservation.put(result, "truncated", truncated);
		JsonValue positions = PlayerObservation.array();
		for (int position : cells) positions.addChild(new JsonValue((long)position));
		result.addChild("cells", positions);
		reset();
		return result;
	}

	synchronized void reset() {
		active = false;
		truncated = false;
		cells.clear();
	}
}
