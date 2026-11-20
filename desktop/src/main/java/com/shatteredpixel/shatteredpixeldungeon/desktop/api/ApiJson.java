package com.shatteredpixel.shatteredpixeldungeon.desktop.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.shatteredpixel.shatteredpixeldungeon.desktop.api.ApiModels.*;

import java.io.IOException;

/** The API's JSON configuration is shared by the runtime, documentation generator, and tests. */
public final class ApiJson {
	public static final ObjectMapper MAPPER = JsonMapper.builder()
			.serializationInclusion(JsonInclude.Include.NON_NULL)
			.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
			.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
			.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
			.build();

	private ApiJson() {}

	public static Action readAction(byte[] bytes) throws IOException {
		Action action = MAPPER.readValue(bytes, Action.class);
		require(action != null, "Expected an action object");
		require(action.observation_id != null && action.observation_id > 0, "observation_id must be positive");
		if (action instanceof CellAction) {
			require(((CellAction) action).cell != null && ((CellAction) action).cell >= 0, "cell must be nonnegative");
		} else if (action instanceof InspectAction) {
			require(((InspectAction) action).cell != null && ((InspectAction) action).cell >= 0, "cell must be nonnegative");
		} else if (action instanceof ItemAction) {
			require(((ItemAction) action).id != null && ((ItemAction) action).action != null, "id and action are required");
		} else if (action instanceof ButtonAction) {
			require(((ButtonAction) action).id != null, "id is required");
		} else if (action instanceof TextAction) {
			String text = ((TextAction) action).text;
			require(text != null && text.length() <= 200, "text is required and limited to 200 characters");
		} else if (action instanceof TalentAction) {
			require(((TalentAction) action).name != null, "name is required");
		}
		return action;
	}

	private static void require(boolean valid, String message) {
		if (!valid) throw new IllegalArgumentException(message);
	}
}
