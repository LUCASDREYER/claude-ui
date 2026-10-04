package claudeui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.minecraft.client.Minecraft;

/**
 * WebSocket link to the claude-ui server on this computer, speaking the same protocol as the web UI.
 * Incoming messages are applied to the {@link Model} on the client thread.
 */
public final class Connection implements WebSocket.Listener {
	public static final int PORT = Integer.getInteger("claudeui.port", 3456);

	public final Model model = new Model(this);
	private volatile WebSocket socket;
	private volatile boolean connecting;
	private final StringBuilder partial = new StringBuilder();
	private CompletableFuture<?> sending = CompletableFuture.completedFuture(null);

	public boolean connected() {
		return socket != null;
	}

	public boolean connecting() {
		return connecting;
	}

	/** Connects once; called when the screen opens, never on a timer. */
	public void connectIfNeeded() {
		if (socket != null || connecting) return;
		connecting = true;
		HttpClient.newHttpClient()
			.newWebSocketBuilder()
			.header("Origin", "http://127.0.0.1:" + PORT) // the server only accepts its own origin
			.connectTimeout(Duration.ofSeconds(3))
			.buildAsync(URI.create("ws://127.0.0.1:" + PORT + "/ws"), this)
			.whenComplete((ws, err) -> Minecraft.getInstance().execute(() -> {
				connecting = false;
				if (err != null) {
					model.onDisconnected("Can't reach claude-ui on 127.0.0.1:" + PORT + ".");
				} else {
					socket = ws;
					model.onConnected();
				}
			}));
	}

	/** Sends one message; sends are chained because a WebSocket allows only one in flight. */
	public synchronized void send(String type, Object... keyValues) {
		WebSocket ws = socket;
		if (ws == null) return;
		JsonObject msg = new JsonObject();
		msg.addProperty("type", type);
		for (int i = 0; i + 1 < keyValues.length; i += 2) {
			String key = (String) keyValues[i];
			Object value = keyValues[i + 1];
			if (value == null) continue;
			if (value instanceof Boolean b) msg.addProperty(key, b);
			else if (value instanceof Number n) msg.addProperty(key, n);
			else if (value instanceof Map<?, ?> map) {
				JsonObject obj = new JsonObject();
				map.forEach((k, v) -> obj.addProperty(String.valueOf(k), String.valueOf(v)));
				msg.add(key, obj);
			} else if (value instanceof JsonArray array) msg.add(key, array);
			else msg.addProperty(key, String.valueOf(value));
		}
		String text = msg.toString();
		sending = sending.handle((v, e) -> null).thenCompose(v -> ws.sendText(text, true));
	}

	@Override
	public void onOpen(WebSocket ws) {
		ws.request(1);
	}

	@Override
	public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
		partial.append(data);
		if (last) {
			String text = partial.toString();
			partial.setLength(0);
			try {
				JsonObject msg = JsonParser.parseString(text).getAsJsonObject();
				Minecraft.getInstance().execute(() -> model.apply(msg));
			} catch (RuntimeException ignored) {
				// not a message we understand
			}
		}
		ws.request(1);
		return null;
	}

	@Override
	public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
		lost();
		return null;
	}

	@Override
	public void onError(WebSocket ws, Throwable error) {
		lost();
	}

	private void lost() {
		socket = null;
		partial.setLength(0);
		Minecraft.getInstance().execute(() -> model.onDisconnected("Lost the connection to claude-ui."));
	}
}
