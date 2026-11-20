package com.shatteredpixel.shatteredpixeldungeon.desktop.api;

import com.shatteredpixel.shatteredpixeldungeon.desktop.api.ApiModels.MovementReceipt;
import com.shatteredpixel.shatteredpixeldungeon.ui.ApiUI;
import org.junit.Test;
import static org.junit.Assert.*;

public class MovementTraceTest {
	@Test public void recordsOnlyActualMoveEventsAndFinalPublicPosition() {
		MovementTrace trace = new MovementTrace();
		trace.begin(3, 0, 54);
		trace.moved(55);
		trace.moved(56);
		MovementReceipt result = trace.finish(3, 0, 56);
		assertEquals(java.util.Arrays.asList(54,55,56), result.cells);
		assertTrue(result.same_floor);
		assertFalse(result.truncated);
		assertNull(trace.finish(3, 0, 56));
	}

	@Test public void interruptedWalkingDoesNotAppendAnUnreachedDestination() {
		MovementTrace trace = new MovementTrace();
		trace.begin(3, 0, 54);
		trace.moved(55);
		MovementReceipt result = trace.finish(3, 0, 55);
		assertEquals(java.util.Arrays.asList(54,55), result.cells);
	}

	@Test public void unexpectedDisplacementIsNotInterpolatedAsWalking() {
		MovementTrace trace = new MovementTrace();
		trace.begin(3, 0, 54);
		trace.moved(100);
		MovementReceipt result = trace.finish(3, 0, 100);
		assertEquals(java.util.Arrays.asList(54,100), result.cells);
	}

	@Test public void changedFloorAndResetCannotCertifyOldFloorMovement() {
		MovementTrace trace = new MovementTrace();
		trace.begin(3, 0, 54);
		MovementReceipt result = trace.finish(4, 0, 20);
		assertFalse(result.same_floor);
		assertEquals(java.util.Arrays.asList(54), result.cells);
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
			assertEquals(java.util.Arrays.asList(54,55), trace.finish(3, 0, 55).cells);
			ApiUI.heroMoveObserver = null;
			ApiUI.heroMoved(56);
		} finally {
			ApiUI.heroMoveObserver = previous;
		}
	}
}
