package com.fentai.arcmenu.protocol;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;

/** Optional project-management channel. Existing menu and Effect protocols remain unchanged. */
public final class WorkspaceProtocol {
    public static final String CHANNEL = "arcmenu:workspace";
    public static final int VERSION = 1;
    private static final int MAGIC = 0x41525753;
    private WorkspaceProtocol() {}
    public sealed interface Packet permits Request, Reply {}
    public record Request(long requestId, String action, String json) implements Packet {}
    public record Reply(long requestId, boolean ok, String action, String json, String error) implements Packet {}

    public static byte[] encode(Packet packet) {
        try {
            var buffer = new ByteArrayOutputStream();
            var out = new DataOutputStream(buffer);
            out.writeInt(MAGIC); out.writeByte(VERSION);
            if (packet instanceof Request p) {
                out.writeByte(1); out.writeLong(p.requestId());
                string(out, p.action(), 64); string(out, p.json(), 26_000);
            } else if (packet instanceof Reply p) {
                out.writeByte(64); out.writeLong(p.requestId()); out.writeBoolean(p.ok());
                string(out, p.action(), 64); string(out, p.json(), EditorProtocol.MAX_PACKET_BYTES - 8192);
                string(out, p.error(), 4096);
            } else throw new IllegalArgumentException("Unknown workspace packet");
            out.flush();
            if (buffer.size() > EditorProtocol.MAX_PACKET_BYTES) throw new IllegalArgumentException("Workspace packet is too large");
            return buffer.toByteArray();
        } catch (IOException error) { throw new IllegalStateException(error); }
    }

    public static Packet decode(byte[] bytes) {
        if (bytes == null || bytes.length > EditorProtocol.MAX_PACKET_BYTES) throw new IllegalArgumentException("Invalid workspace packet size");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != MAGIC || in.readUnsignedByte() != VERSION) throw new IllegalArgumentException("Unsupported workspace protocol");
            Packet packet = switch (in.readUnsignedByte()) {
                case 1 -> new Request(in.readLong(), string(in, 64), string(in, 26_000));
                case 64 -> new Reply(in.readLong(), in.readBoolean(), string(in, 64), string(in, EditorProtocol.MAX_PACKET_BYTES - 8192), string(in, 4096));
                default -> throw new IllegalArgumentException("Unknown workspace packet");
            };
            if (in.available() != 0) throw new IllegalArgumentException("Trailing workspace packet data");
            return packet;
        } catch (IOException error) { throw new IllegalArgumentException("Truncated workspace packet", error); }
    }
    private static void string(DataOutputStream out, String value, int maximum) throws IOException {
        if (value == null) throw new IllegalArgumentException("Workspace strings cannot be null");
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maximum) throw new IllegalArgumentException("Workspace string is too long");
        out.writeInt(bytes.length); out.write(bytes);
    }
    private static String string(DataInputStream in, int maximum) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > maximum || length > in.available()) throw new IllegalArgumentException("Invalid workspace string length");
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(in.readNBytes(length))).toString(); }
        catch (CharacterCodingException error) { throw new IllegalArgumentException("Invalid workspace UTF-8", error); }
    }
}
