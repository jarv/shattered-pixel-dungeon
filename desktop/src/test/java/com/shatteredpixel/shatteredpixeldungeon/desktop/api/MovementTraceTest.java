package com.shatteredpixel.shatteredpixeldungeon.desktop.api;

import com.badlogic.gdx.utils.JsonValue;
import com.shatteredpixel.shatteredpixeldungeon.ui.ApiUI;
import org.junit.Test;
import static org.junit.Assert.*;

public class MovementTraceTest {
	@Test public void recordsOnlyActualMoveEventsAndFinalPublicPosition() {
		MovementTrace trace = new MovementTrace();
		trace.begin(3, 0, 54);
		trace.moved(55);
		trace.moved(56);
		JsonValue result = trace.finish(3, 0, 56);
		assertArrayEquals(new int[]{54,55,56}, result.get("cells").asIntArray());
		assertTrue(result.getBoolean("same_floor"));
		assertFalse(result.getBoolean("truncated"));
		assertNull(trace.finish(3, 0, 56));
	}

	@Test public void interruptedWalkingDoesNotAppendAnUnreachedDestination() {
		MovementTrace trace = new MovementTrace();
		trace.begin(3, 0, 54);
		trace.moved(55);
		JsonValue result = trace.finish(3, 0, 55);
		assertArrayEquals(new int[]{54,55}, result.get("cells").asIntArray());
	}

	@Test public void unexpectedDisplacementIsNotInterpolatedAsWalking() {
		MovementTrace trace = new MovementTrace();
		trace.begin(3, 0, 54);
		trace.moved(100);
		JsonValue result = trace.finish(3, 0, 100);
		assertArrayEquals(new int[]{54,100}, result.get("cells").asIntArray());
	}

	@Test public void changedFloorAndResetCannotCertifyOldFloorMovement() {
		MovementTrace trace = new MovementTrace();
		trace.begin(3, 0, 54);
		JsonValue result = trace.finish(4, 0, 20);
		assertFalse(result.getBoolean("same_floor"));
		assertArrayEquals(new int[]{54}, result.get("cells").asIntArray());
		trace.begin(4, 0, 20);
		trace.reset();
		trace.moved(21);
		assertNull(trace.finish(4, 0, 21));
	}

	@Test public void publicHeroMovementObserverHasNoEffectWithoutSubscriber() {
		java.util.function.IntConsumer previous = ApiUI.heroMoveObserver;
		try {
			MovementTrace trace = new MovementTrace();
			trace.begin(3, 0, 54);
			ApiUI.heroMoveObserver = trace::moved;
			ApiUI.heroMoved(55);
			assertArrayEquals(new int[]{54,55}, trace.finish(3, 0, 55).get("cells").asIntArray());
			ApiUI.heroMoveObserver = null;
			ApiUI.heroMoved(56);
		} finally {
			ApiUI.heroMoveObserver = previous;
		}
	}
}
