package claudeui;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Never actually sent. A server running this mod (including single-player) registers a receiver for it, which
 * Fabric advertises to the client; that's how the client knows the server has the Claude Screen block.
 */
public record HelloPayload() implements CustomPacketPayload {
	public static final HelloPayload INSTANCE = new HelloPayload();
	public static final Type<HelloPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("claudeui", "hello"));
	public static final StreamCodec<RegistryFriendlyByteBuf, HelloPayload> CODEC = StreamCodec.unit(INSTANCE);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
