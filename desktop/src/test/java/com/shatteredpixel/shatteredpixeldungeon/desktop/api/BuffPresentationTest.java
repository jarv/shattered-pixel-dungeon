package com.shatteredpixel.shatteredpixeldungeon.desktop.api;

import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.watabou.noosa.Image;
import org.junit.Test;
import static org.junit.Assert.*;

public class BuffPresentationTest {
	private static class VisibleBuff extends Buff {
		@Override public int icon() { return BuffIndicator.HUNGER; }
		@Override public String name() { return "hungry"; }
		@Override public String desc() { return "Public description"; }
	}

	@Test public void exportsAuthoritativeIconWithoutDefaultTint() {
		ApiModels.BuffState state = PlayerObservation.buffState(new VisibleBuff());
		assertEquals(Integer.valueOf(BuffIndicator.HUNGER), state.icon_id);
		assertEquals("hungry", state.name);
		assertNull(state.icon_tint);
		ApiContractTest.assertConforms("BuffState", state);
	}

	@Test public void exportsGameColorTransformAndChangingIcon() {
		Buff buff = new VisibleBuff() {
			@Override public int icon() { return BuffIndicator.STARVATION; }
			@Override public void tintIcon(Image icon) { icon.hardlight(1f, .5f, 0f); }
		};
		ApiModels.BuffState state = PlayerObservation.buffState(buff);
		assertEquals(Integer.valueOf(BuffIndicator.STARVATION), state.icon_id);
		assertArrayEquals(new float[]{1f, .5f, 0f, 0f, 0f, 0f}, state.icon_tint, 0f);
		ApiContractTest.assertConforms("BuffState", state);
	}
}
