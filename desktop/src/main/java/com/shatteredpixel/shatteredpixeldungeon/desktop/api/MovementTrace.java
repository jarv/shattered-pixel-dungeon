package com.shatteredpixel.shatteredpixeldungeon.desktop.api;

import com.shatteredpixel.shatteredpixeldungeon.desktop.api.ApiModels.MovementReceipt;
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

	synchronized MovementReceipt finish(int endDepth, int endBranch, int cell) {
		if (!active) return null;
		boolean same = endDepth == depth && endBranch == branch;
		if (same) moved(cell);
		MovementReceipt result = new MovementReceipt();
		result.depth = depth;
		result.branch = branch;
		result.same_floor = same;
		result.truncated = truncated;
		result.cells.addAll(cells);
		reset();
		return result;
	}

	synchronized void reset() {
		active = false;
		truncated = false;
		cells.clear();
	}
}
