package com.fentai.arcmenu.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Versioned, dependency-free protocol shared by Paper and every Fabric adapter. */
public final class EditorProtocol {
    public static final String CHANNEL = "arcmenu:editor";
    public static final int VERSION = 8;
    public static final int MAX_PACKET_BYTES = 1_048_576;
    public static final int MAX_STRING_BYTES = 16_384;
    public static final int MAX_FRAME_DATA = 28_000;

    public static final byte TAB_FRONTEND = 0;
    public static final byte TAB_BACKEND = 1;

    public static final byte KIND_GROUP = 0;
    public static final byte KIND_RECTANGLE = 1;
    public static final byte KIND_FRAME = 2;
    public static final byte KIND_TEXT = 3;
    public static final byte KIND_ITEM = 4;
    public static final byte KIND_BLOCK = 5;
    public static final byte KIND_REGION = 6;
    public static final byte KIND_IMAGE = 7;
    public static final byte KIND_LINE = 8;

    public static final byte OP_MOVE = 1;
    public static final byte OP_RESIZE = 2;
    public static final byte OP_UNDO = 3;
    public static final byte OP_REDO = 4;
    public static final byte OP_SAVE = 5;
    public static final byte OP_APPLY = 6;
    public static final byte OP_PROBE = 7;
    public static final byte OP_TAB = 8;
    public static final byte OP_CREATE = 9;
    public static final byte OP_DELETE = 10;
    public static final byte OP_PROPERTY = 11;
    public static final byte OP_TEMPLATE_SAVE = 12;
    public static final byte OP_TEMPLATE_INSTANTIATE = 13;
    public static final byte OP_DELETE_MANY = 14;
    public static final byte OP_DUPLICATE = 15;
    public static final byte OP_GROUP = 16;
    public static final byte OP_REORDER = 17;
    public static final byte OP_TEMPLATE_DELETE = 18;
    public static final byte OP_REPARENT = 19;

    public static final byte PROPERTY_TEXT = 0;
    public static final byte PROPERTY_NUMBER = 1;
    public static final byte PROPERTY_INTEGER = 2;
    public static final byte PROPERTY_BOOLEAN = 3;
    public static final byte PROPERTY_COLOR = 4;
    public static final byte PROPERTY_CHOICE = 5;
    public static final byte PROPERTY_MULTILINE = 6;

    private static final int MAGIC = 0x41524338; // ARC8
    private static final int FRAME_MAGIC = 0x41524346; // ARCF
    private static final byte HELLO = 1;
    private static final byte MOVE = 2;
    private static final byte RESIZE = 3;
    private static final byte SWITCH_TAB = 4;
    private static final byte UNDO = 5;
    private static final byte REDO = 6;
    private static final byte SAVE = 7;
    private static final byte APPLY = 8;
    private static final byte CLOSE = 9;
    private static final byte PROBE = 10;
    private static final byte CREATE = 11;
    private static final byte DELETE = 12;
    private static final byte SET_PROPERTY = 13;
    private static final byte SAVE_TEMPLATE = 14;
    private static final byte INSTANTIATE_TEMPLATE = 15;
    private static final byte DELETE_MANY = 16;
    private static final byte DUPLICATE = 17;
    private static final byte GROUP = 18;
    private static final byte REORDER = 19;
    private static final byte DELETE_TEMPLATE = 20;
    private static final byte REPARENT = 21;
    private static final byte SNAPSHOT = 64;
    private static final byte ACK = 65;
    private static final byte ERROR = 66;

    private EditorProtocol() {}

    public record WireFrame(int messageId, int index, int count, byte[] data) {
        public WireFrame {
            data = data.clone();
            if (index < 0 || count < 1 || index >= count || count > 256 || data.length > MAX_FRAME_DATA) {
                throw new IllegalArgumentException("invalid editor frame");
            }
        }
        @Override public byte[] data() { return data.clone(); }
    }

    /** Splits a logical response below the conservative Bukkit plugin-message payload ceiling. */
    public static List<byte[]> frame(int messageId, byte[] packet) {
        if (packet.length > MAX_PACKET_BYTES) throw new IllegalArgumentException("editor packet too large");
        int count = Math.max(1, (packet.length + MAX_FRAME_DATA - 1) / MAX_FRAME_DATA);
        if (count > 256) throw new IllegalArgumentException("too many editor frames");
        var result = new ArrayList<byte[]>(count);
        for (int index = 0; index < count; index++) {
            int start = index * MAX_FRAME_DATA;
            int length = Math.min(MAX_FRAME_DATA, packet.length - start);
            try {
                var bytes = new ByteArrayOutputStream(length + 16);
                var out = new DataOutputStream(bytes);
                out.writeInt(FRAME_MAGIC); out.writeByte(VERSION); out.writeInt(messageId);
                out.writeShort(index); out.writeShort(count); out.writeShort(length); out.write(packet, start, length);
                out.flush(); result.add(bytes.toByteArray());
            } catch (IOException impossible) { throw new IllegalStateException(impossible); }
        }
        return result;
    }

    public static WireFrame decodeFrame(byte[] bytes) {
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != FRAME_MAGIC || in.readUnsignedByte() != VERSION) throw new IllegalArgumentException("invalid editor frame header");
            int messageId = in.readInt(); int index = in.readUnsignedShort(); int count = in.readUnsignedShort(); int length = in.readUnsignedShort();
            if (length > MAX_FRAME_DATA || length != in.available()) throw new IllegalArgumentException("invalid editor frame length");
            return new WireFrame(messageId, index, count, in.readNBytes(length));
        } catch (IOException error) { throw new IllegalArgumentException("truncated editor frame", error); }
    }

    public sealed interface Packet permits HelloPacket, MovePacket, ResizePacket, SwitchTabPacket,
            UndoPacket, RedoPacket, SavePacket, ApplyPacket, ClosePacket, ProbePacket, CreatePacket,
            DeletePacket, DeleteManyPacket, DuplicatePacket, GroupPacket, ReorderPacket, ReparentPacket,
            SetPropertyPacket, SaveTemplatePacket, InstantiateTemplatePacket, DeleteTemplatePacket,
            SnapshotPacket, AckPacket, ErrorPacket {}

    public record HelloPacket(String clientVersion) implements Packet {}
    public record Pointer(double mouseX, double mouseY, double viewportX, double viewportY,
                          double viewportWidth, double viewportHeight,
                          double clientX, double clientY) {}
    public record MovePacket(long revision, byte tab, String nodeId, Pointer pointer,
                             double grabOffsetX, double grabOffsetY,
                             long gestureId, boolean finalUpdate) implements Packet {
        public MovePacket(long revision, byte tab, String nodeId, Pointer pointer,
                          double grabOffsetX, double grabOffsetY) {
            this(revision, tab, nodeId, pointer, grabOffsetX, grabOffsetY, 0, true);
        }
    }
    public record ResizePacket(long revision, byte tab, String nodeId, Pointer pointer,
                               long gestureId, boolean finalUpdate) implements Packet {
        public ResizePacket(long revision, byte tab, String nodeId, Pointer pointer) {
            this(revision, tab, nodeId, pointer, 0, true);
        }
    }
    public record SwitchTabPacket(byte tab) implements Packet {}
    public record UndoPacket(long revision) implements Packet {}
    public record RedoPacket(long revision) implements Packet {}
    public record SavePacket(long revision) implements Packet {}
    public record ApplyPacket(long revision) implements Packet {}
    public record ClosePacket() implements Packet {}
    public record ProbePacket(long revision, byte tab, Pointer pointer) implements Packet {}
    public record CreatePacket(long revision, byte tab, byte kind, String parentId, String initialSource) implements Packet {
        public CreatePacket(long revision, byte tab, byte kind, String parentId) {
            this(revision, tab, kind, parentId, "");
        }
    }
    public record DeletePacket(long revision, byte tab, String nodeId) implements Packet {}
    public record DeleteManyPacket(long revision, byte tab, List<String> nodeIds) implements Packet {
        public DeleteManyPacket { nodeIds = List.copyOf(nodeIds); }
    }
    /** Duplicates selected roots. When preserveParents is false every copy is inserted in targetParentId. */
    public record DuplicatePacket(long revision, byte tab, List<String> nodeIds,
                                  String targetParentId, boolean preserveParents) implements Packet {
        public DuplicatePacket { nodeIds = List.copyOf(nodeIds); }
    }
    public record GroupPacket(long revision, List<String> nodeIds) implements Packet {
        public GroupPacket { nodeIds = List.copyOf(nodeIds); }
    }
    /** Reorders nodes inside their existing parent and inserts them immediately before beforeId, or at the end when blank. */
    public record ReorderPacket(long revision, byte tab, List<String> nodeIds, String beforeId) implements Packet {
        public ReorderPacket { nodeIds = List.copyOf(nodeIds); }
    }
    /** Moves selected frontend roots to a hierarchy level while preserving their world transforms. */
    public record ReparentPacket(long revision, List<String> nodeIds, String targetParentId,
                                 String beforeId) implements Packet {
        public ReparentPacket { nodeIds = List.copyOf(nodeIds); }
    }
    public record SetPropertyPacket(long revision, byte tab, String nodeId, String key, String value,
                                    long gestureId, boolean finalUpdate) implements Packet {
        public SetPropertyPacket(long revision, byte tab, String nodeId, String key, String value) {
            this(revision, tab, nodeId, key, value, 0, true);
        }
    }
    public record SaveTemplatePacket(long revision, String nodeId, String templateId) implements Packet {}
    public record InstantiateTemplatePacket(long revision, String templateId) implements Packet {}
    public record DeleteTemplatePacket(long revision, String templateId) implements Packet {}

    public record PropertySnapshot(String key, byte type, String value, List<String> choices) {
        public PropertySnapshot {
            choices = List.copyOf(choices);
        }
        public PropertySnapshot(String key, byte type, String value) { this(key, type, value, List.of()); }
    }

    public record NodeSnapshot(String id, String parentId, byte kind, double x, double y,
                               double width, double height, double rotationZ,
                               boolean visible, boolean locked, List<PropertySnapshot> properties) {
        public NodeSnapshot {
            properties = List.copyOf(properties);
        }
        public NodeSnapshot(String id, String parentId, byte kind, double x, double y,
                            double width, double height, double rotationZ,
                            boolean visible, boolean locked) {
            this(id, parentId, kind, x, y, width, height, rotationZ, visible, locked, List.of());
        }
    }
    public record ImageSnapshot(String path, int width, int height) {}
    public record TemplateSnapshot(String id, String rootId, int nodeCount) {}
    public record SnapshotPacket(long revision, String menuId, double canvasWidth, double canvasHeight,
                                 boolean dirty, boolean saved, String serverVersion,
                                 List<NodeSnapshot> frontend, List<NodeSnapshot> backend,
                                 List<ImageSnapshot> images, List<TemplateSnapshot> templates) implements Packet {
        public SnapshotPacket {
            frontend = List.copyOf(frontend);
            backend = List.copyOf(backend);
            images = List.copyOf(images);
            templates = List.copyOf(templates);
        }
        public SnapshotPacket(long revision, String menuId, double canvasWidth, double canvasHeight,
                              boolean dirty, boolean saved, String serverVersion,
                              List<NodeSnapshot> frontend, List<NodeSnapshot> backend) {
            this(revision, menuId, canvasWidth, canvasHeight, dirty, saved, serverVersion,
                    frontend, backend, List.of(), List.of());
        }
    }
    public record AckPacket(byte operation, long revision, String nodeId,
                            double x, double y, double width, double height,
                            double serverX, double serverY, String hitId,
                            boolean dirty, boolean saved, String message) implements Packet {}
    public record ErrorPacket(long revision, String message) implements Packet {}

    public static byte[] encode(Packet packet) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeByte(VERSION);
            switch (packet) {
                case HelloPacket value -> { out.writeByte(HELLO); writeString(out, value.clientVersion()); }
                case MovePacket value -> {
                    out.writeByte(MOVE); out.writeLong(value.revision()); out.writeByte(value.tab());
                    writeString(out, value.nodeId()); writePointer(out, value.pointer());
                    out.writeDouble(value.grabOffsetX()); out.writeDouble(value.grabOffsetY());
                    out.writeLong(value.gestureId()); out.writeBoolean(value.finalUpdate());
                }
                case ResizePacket value -> {
                    out.writeByte(RESIZE); out.writeLong(value.revision()); out.writeByte(value.tab());
                    writeString(out, value.nodeId()); writePointer(out, value.pointer());
                    out.writeLong(value.gestureId()); out.writeBoolean(value.finalUpdate());
                }
                case SwitchTabPacket value -> { out.writeByte(SWITCH_TAB); out.writeByte(value.tab()); }
                case UndoPacket value -> { out.writeByte(UNDO); out.writeLong(value.revision()); }
                case RedoPacket value -> { out.writeByte(REDO); out.writeLong(value.revision()); }
                case SavePacket value -> { out.writeByte(SAVE); out.writeLong(value.revision()); }
                case ApplyPacket value -> { out.writeByte(APPLY); out.writeLong(value.revision()); }
                case ClosePacket ignored -> out.writeByte(CLOSE);
                case ProbePacket value -> {
                    out.writeByte(PROBE); out.writeLong(value.revision()); out.writeByte(value.tab());
                    writePointer(out, value.pointer());
                }
                case CreatePacket value -> {
                    out.writeByte(CREATE); out.writeLong(value.revision()); out.writeByte(value.tab());
                    out.writeByte(value.kind()); writeString(out, value.parentId()); writeString(out, value.initialSource());
                }
                case DeletePacket value -> {
                    out.writeByte(DELETE); out.writeLong(value.revision()); out.writeByte(value.tab()); writeString(out, value.nodeId());
                }
                case DeleteManyPacket value -> {
                    out.writeByte(DELETE_MANY); out.writeLong(value.revision()); out.writeByte(value.tab()); writeStrings(out, value.nodeIds());
                }
                case DuplicatePacket value -> {
                    out.writeByte(DUPLICATE); out.writeLong(value.revision()); out.writeByte(value.tab());
                    writeStrings(out, value.nodeIds()); writeString(out, value.targetParentId()); out.writeBoolean(value.preserveParents());
                }
                case GroupPacket value -> {
                    out.writeByte(GROUP); out.writeLong(value.revision()); writeStrings(out, value.nodeIds());
                }
                case ReorderPacket value -> {
                    out.writeByte(REORDER); out.writeLong(value.revision()); out.writeByte(value.tab());
                    writeStrings(out, value.nodeIds()); writeString(out, value.beforeId());
                }
                case ReparentPacket value -> {
                    out.writeByte(REPARENT); out.writeLong(value.revision());
                    writeStrings(out, value.nodeIds()); writeString(out, value.targetParentId());
                    writeString(out, value.beforeId());
                }
                case SetPropertyPacket value -> {
                    out.writeByte(SET_PROPERTY); out.writeLong(value.revision()); out.writeByte(value.tab());
                    writeString(out, value.nodeId()); writeString(out, value.key()); writeString(out, value.value());
                    out.writeLong(value.gestureId()); out.writeBoolean(value.finalUpdate());
                }
                case SaveTemplatePacket value -> {
                    out.writeByte(SAVE_TEMPLATE); out.writeLong(value.revision()); writeString(out, value.nodeId()); writeString(out, value.templateId());
                }
                case InstantiateTemplatePacket value -> {
                    out.writeByte(INSTANTIATE_TEMPLATE); out.writeLong(value.revision()); writeString(out, value.templateId());
                }
                case DeleteTemplatePacket value -> {
                    out.writeByte(DELETE_TEMPLATE); out.writeLong(value.revision()); writeString(out, value.templateId());
                }
                case SnapshotPacket value -> {
                    out.writeByte(SNAPSHOT); out.writeLong(value.revision()); writeString(out, value.menuId());
                    out.writeDouble(value.canvasWidth()); out.writeDouble(value.canvasHeight());
                    out.writeBoolean(value.dirty()); out.writeBoolean(value.saved()); writeString(out, value.serverVersion());
                    writeNodes(out, value.frontend()); writeNodes(out, value.backend());
                    writeImages(out, value.images()); writeTemplates(out, value.templates());
                }
                case AckPacket value -> {
                    out.writeByte(ACK); out.writeByte(value.operation()); out.writeLong(value.revision());
                    writeString(out, value.nodeId()); out.writeDouble(value.x()); out.writeDouble(value.y());
                    out.writeDouble(value.width()); out.writeDouble(value.height());
                    out.writeDouble(value.serverX()); out.writeDouble(value.serverY()); writeString(out, value.hitId());
                    out.writeBoolean(value.dirty()); out.writeBoolean(value.saved()); writeString(out, value.message());
                }
                case ErrorPacket value -> { out.writeByte(ERROR); out.writeLong(value.revision()); writeString(out, value.message()); }
            }
            out.flush();
            if (bytes.size() > MAX_PACKET_BYTES) throw new IllegalArgumentException("editor packet exceeds " + MAX_PACKET_BYTES + " bytes");
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static Packet decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_PACKET_BYTES) throw new IllegalArgumentException("invalid editor packet size");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != MAGIC) throw new IllegalArgumentException("invalid editor packet magic");
            int version = in.readUnsignedByte();
            if (version != VERSION) throw new IllegalArgumentException("protocol mismatch: client=" + version + ", server=" + VERSION);
            Packet result = switch (in.readUnsignedByte()) {
                case HELLO -> new HelloPacket(readString(in));
                case MOVE -> new MovePacket(in.readLong(), in.readByte(), readString(in), readPointer(in),
                        in.readDouble(), in.readDouble(), in.readLong(), in.readBoolean());
                case RESIZE -> new ResizePacket(in.readLong(), in.readByte(), readString(in), readPointer(in), in.readLong(), in.readBoolean());
                case SWITCH_TAB -> new SwitchTabPacket(in.readByte());
                case UNDO -> new UndoPacket(in.readLong());
                case REDO -> new RedoPacket(in.readLong());
                case SAVE -> new SavePacket(in.readLong());
                case APPLY -> new ApplyPacket(in.readLong());
                case CLOSE -> new ClosePacket();
                case PROBE -> new ProbePacket(in.readLong(), in.readByte(), readPointer(in));
                case CREATE -> new CreatePacket(in.readLong(), in.readByte(), in.readByte(), readString(in), readString(in));
                case DELETE -> new DeletePacket(in.readLong(), in.readByte(), readString(in));
                case DELETE_MANY -> new DeleteManyPacket(in.readLong(), in.readByte(), readStrings(in));
                case DUPLICATE -> new DuplicatePacket(in.readLong(), in.readByte(), readStrings(in), readString(in), in.readBoolean());
                case GROUP -> new GroupPacket(in.readLong(), readStrings(in));
                case REORDER -> new ReorderPacket(in.readLong(), in.readByte(), readStrings(in), readString(in));
                case REPARENT -> new ReparentPacket(in.readLong(), readStrings(in), readString(in), readString(in));
                case SET_PROPERTY -> new SetPropertyPacket(in.readLong(), in.readByte(), readString(in), readString(in), readString(in),
                        in.readLong(), in.readBoolean());
                case SAVE_TEMPLATE -> new SaveTemplatePacket(in.readLong(), readString(in), readString(in));
                case INSTANTIATE_TEMPLATE -> new InstantiateTemplatePacket(in.readLong(), readString(in));
                case DELETE_TEMPLATE -> new DeleteTemplatePacket(in.readLong(), readString(in));
                case SNAPSHOT -> new SnapshotPacket(in.readLong(), readString(in), in.readDouble(), in.readDouble(),
                        in.readBoolean(), in.readBoolean(), readString(in), readNodes(in), readNodes(in), readImages(in), readTemplates(in));
                case ACK -> new AckPacket(in.readByte(), in.readLong(), readString(in), in.readDouble(), in.readDouble(),
                        in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(), readString(in),
                        in.readBoolean(), in.readBoolean(), readString(in));
                case ERROR -> new ErrorPacket(in.readLong(), readString(in));
                default -> throw new IllegalArgumentException("unknown editor packet type");
            };
            if (in.available() != 0) throw new IllegalArgumentException("trailing editor packet data");
            return result;
        } catch (IOException error) {
            throw new IllegalArgumentException("truncated editor packet", error);
        }
    }

    private static void writePointer(DataOutputStream out, Pointer value) throws IOException {
        out.writeDouble(value.mouseX()); out.writeDouble(value.mouseY());
        out.writeDouble(value.viewportX()); out.writeDouble(value.viewportY());
        out.writeDouble(value.viewportWidth()); out.writeDouble(value.viewportHeight());
        out.writeDouble(value.clientX()); out.writeDouble(value.clientY());
    }

    private static Pointer readPointer(DataInputStream in) throws IOException {
        return new Pointer(in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(),
                in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble());
    }

    private static void writeNodes(DataOutputStream out, List<NodeSnapshot> nodes) throws IOException {
        if (nodes.size() > 10_000) throw new IllegalArgumentException("too many editor nodes");
        out.writeInt(nodes.size());
        for (var node : nodes) {
            writeString(out, node.id()); writeString(out, node.parentId()); out.writeByte(node.kind());
            out.writeDouble(node.x()); out.writeDouble(node.y()); out.writeDouble(node.width()); out.writeDouble(node.height());
            out.writeDouble(node.rotationZ()); out.writeBoolean(node.visible()); out.writeBoolean(node.locked());
            writeProperties(out, node.properties());
        }
    }

    private static List<NodeSnapshot> readNodes(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > 10_000) throw new IllegalArgumentException("invalid editor node count");
        var result = new ArrayList<NodeSnapshot>(count);
        for (int i = 0; i < count; i++) result.add(new NodeSnapshot(
                readString(in), readString(in), in.readByte(), in.readDouble(), in.readDouble(),
                in.readDouble(), in.readDouble(), in.readDouble(), in.readBoolean(), in.readBoolean(), readProperties(in)));
        return result;
    }

    private static void writeProperties(DataOutputStream out, List<PropertySnapshot> properties) throws IOException {
        if (properties.size() > 256) throw new IllegalArgumentException("too many editor properties");
        out.writeShort(properties.size());
        for (var property : properties) {
            writeString(out, property.key()); out.writeByte(property.type()); writeString(out, property.value());
            if (property.choices().size() > 256) throw new IllegalArgumentException("too many editor property choices");
            out.writeShort(property.choices().size());
            for (String choice : property.choices()) writeString(out, choice);
        }
    }

    private static List<PropertySnapshot> readProperties(DataInputStream in) throws IOException {
        int count = in.readUnsignedShort();
        if (count > 256) throw new IllegalArgumentException("invalid editor property count");
        var result = new ArrayList<PropertySnapshot>(count);
        for (int i = 0; i < count; i++) {
            String key = readString(in); byte type = in.readByte(); String value = readString(in);
            int choiceCount = in.readUnsignedShort();
            if (choiceCount > 256) throw new IllegalArgumentException("invalid editor choice count");
            var choices = new ArrayList<String>(choiceCount);
            for (int choice = 0; choice < choiceCount; choice++) choices.add(readString(in));
            result.add(new PropertySnapshot(key, type, value, choices));
        }
        return result;
    }

    private static void writeImages(DataOutputStream out, List<ImageSnapshot> images) throws IOException {
        if (images.size() > 10_000) throw new IllegalArgumentException("too many editor images");
        out.writeInt(images.size());
        for (var image : images) { writeString(out, image.path()); out.writeInt(image.width()); out.writeInt(image.height()); }
    }

    private static List<ImageSnapshot> readImages(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > 10_000) throw new IllegalArgumentException("invalid editor image count");
        var result = new ArrayList<ImageSnapshot>(count);
        for (int i = 0; i < count; i++) result.add(new ImageSnapshot(readString(in), in.readInt(), in.readInt()));
        return result;
    }

    private static void writeTemplates(DataOutputStream out, List<TemplateSnapshot> templates) throws IOException {
        if (templates.size() > 10_000) throw new IllegalArgumentException("too many editor templates");
        out.writeInt(templates.size());
        for (var template : templates) { writeString(out, template.id()); writeString(out, template.rootId()); out.writeInt(template.nodeCount()); }
    }

    private static List<TemplateSnapshot> readTemplates(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > 10_000) throw new IllegalArgumentException("invalid editor template count");
        var result = new ArrayList<TemplateSnapshot>(count);
        for (int i = 0; i < count; i++) result.add(new TemplateSnapshot(readString(in), readString(in), in.readInt()));
        return result;
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        var bytes = (value == null ? "" : value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) throw new IllegalArgumentException("editor string too long");
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static void writeStrings(DataOutputStream out, List<String> values) throws IOException {
        if (values.isEmpty() || values.size() > 10_000) throw new IllegalArgumentException("invalid editor string list size");
        out.writeShort(values.size());
        for (String value : values) writeString(out, value);
    }

    private static List<String> readStrings(DataInputStream in) throws IOException {
        int count = in.readUnsignedShort();
        if (count < 1 || count > 10_000) throw new IllegalArgumentException("invalid editor string list size");
        var result = new ArrayList<String>(count);
        for (int index = 0; index < count; index++) result.add(readString(in));
        return result;
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readUnsignedShort();
        if (length > MAX_STRING_BYTES) throw new IllegalArgumentException("editor string too long");
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new IOException("truncated editor string");
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}
