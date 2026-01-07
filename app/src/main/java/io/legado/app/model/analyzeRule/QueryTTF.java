package io.legado.app.model.analyzeRule;

import android.os.ConditionVariable;
import android.util.Log;

import androidx.annotation.Keep;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Keep
@SuppressWarnings({"FieldCanBeLocal", "unused"})
public class QueryTTF {
    private static final String TAG = "QueryTTF";

    /**
     * 文件头
     *
     * @url <a href="https://learn.microsoft.com/zh-cn/typography/opentype/spec/otff">Microsoft opentype 字体文档</a>
     */
    private static class Header {
        /**
         * uint32   字体版本 0x00010000 (ttf)
         */
        public long sfntVersion;
        /**
         * uint16   Number of tables.
         */
        public int numTables;
        /**
         * uint16
         */
        public int searchRange;
        /**
         * uint16
         */
        public int entrySelector;
        /**
         * uint16
         */
        public int rangeShift;
    }

    /**
     * 数据表目录
     */
    private static class Directory {
        /**
         * uint32 (表标识符)
         */
        public String tableTag;
        /**
         * uint32 (该表的校验和)
         */
        public int checkSum;
        /**
         * uint32 (TTF文件 Bytes 数据索引 0 开始的偏移地址)
         */
        public int offset;
        /**
         * uint32 (该表的长度)
         */
        public int length;
    }

    private static class NameLayout {
        public int format;
        public int count;
        public int stringOffset;
        public NameRecord[] records; // 改为数组
    }

    private static class NameRecord {
        public int platformID;           // 平台标识符<0:Unicode, 1:Mac, 2:ISO, 3:Windows, 4:Custom>
        public int encodingID;           // 编码标识符
        public int languageID;           // 语言标识符
        public int nameID;               // 名称标识符
        public int length;               // 名称字符串的长度
        public int offset;               // 名称字符串相对于stringOffset的字节偏移量
    }

    /**
     * Font Header Table
     */
    private static class HeadLayout {
        /**
         * uint16
         */
        public int majorVersion;
        /**
         * uint16
         */
        public int minorVersion;
        /**
         * uint16
         */
        public int fontRevision;
        /**
         * uint32
         */
        public int checkSumAdjustment;
        /**
         * uint32
         */
        public int magicNumber;
        /**
         * uint16
         */
        public int flags;
        /**
         * uint16
         */
        public int unitsPerEm;
        /**
         * long
         */
        public long created;
        /**
         * long
         */
        public long modified;
        /**
         * int16
         */
        public short xMin;
        /**
         * int16
         */
        public short yMin;
        /**
         * int16
         */
        public short xMax;
        /**
         * int16
         */
        public short yMax;
        /**
         * uint16
         */
        public int macStyle;
        /**
         * uint16
         */
        public int lowestRecPPEM;
        /**
         * int16
         */
        public short fontDirectionHint;
        /**
         * int16
         * <p> 0 表示短偏移 (Offset16)，1 表示长偏移 (Offset32)。
         */
        public short indexToLocFormat;
        /**
         * int16
         */
        public short glyphDataFormat;
    }

    /**
     * Maximum Profile
     */
    private static class MaxpLayout {
        /**
         * uint32   高16位表示整数，低16位表示小数
         */
        public int version;
        /**
         * uint16   字体中的字形数量
         */
        public int numGlyphs;
        /**
         * uint16   非复合字形中包含的最大点数。点是构成字形轮廓的基本单位。
         */
        public int maxPoints;
        /**
         * uint16   非复合字形中包含的最大轮廓数。轮廓是由一系列点连接形成的封闭曲线。
         */
        public int maxContours;
        /**
         * uint16   复合字形中包含的最大点数。复合字形是由多个简单字形组合而成的。
         */
        public int maxCompositePoints;
        /**
         * uint16   复合字形中包含的最大轮廓数。
         */
        public int maxCompositeContours;
        /**
         * uint16
         */
        public int maxZones;
        /**
         * uint16
         */
        public int maxTwilightPoints;
        /**
         * uint16
         */
        public int maxStorage;
        /**
         * uint16
         */
        public int maxFunctionDefs;
        /**
         * uint16
         */
        public int maxInstructionDefs;
        /**
         * uint16
         */
        public int maxStackElements;
        /**
         * uint16
         */
        public int maxSizeOfInstructions;
        /**
         * uint16   任何复合字形在“顶层”引用的最大组件数。
         */
        public int maxComponentElements;
        /**
         * uint16   递归的最大层数；简单组件为1。
         */
        public int maxComponentDepth;
    }

    /**
     * 字符到字形索引映射表
     */
    private static class CmapLayout {
        /**
         * uint16
         */
        public int version;
        /**
         * uint16   后面的编码表的数量
         */
        public int numTables;
        public CmapRecord[] records; // 改为数组
        public HashMap<Integer, CmapFormat> tables = new HashMap<>();
    }

    /**
     * Encoding records and encodings
     */
    private static class CmapRecord {
        /**
         * uint16   Platform ID.
         * <p> 0、Unicode
         * <p> 1、Macintosh
         * <p> 2、ISO
         * <p> 3、Windows
         * <p> 4、Custom
         */
        public int platformID;
        /**
         * uint16   Platform-specific encoding ID.
         * <p> platform ID = 3
         * <p>  0、Symbol
         * <p>  1、Unicode BMP
         * <p>  2、ShiftJIS
         * <p>  3、PRC
         * <p>  4、Big5
         * <p>  5、Wansung
         * <p>  6、Johab
         * <p>  7、Reserved
         * <p>  8、Reserved
         * <p>  9、Reserved
         * <p> 10、Unicode full repertoire
         */
        public int encodingID;
        /**
         * uint32   从 cmap 表开头到子表的字节偏移量
         */
        public int offset;
    }

    private static class CmapFormat {
        /**
         * uint16
         * <p> cmapFormat 子表的格式类型
         */
        public int format;
        /**
         * uint16
         * <p> 这个 Format 表的长度（以字节为单位）
         */
        public int length;
        /**
         * uint16
         * <p> 仅 platformID=1 时有效
         */
        public int language;
        /**
         * uint16[256]
         * <p> 仅 Format=2
         * <p> 将高字节映射到 subHeaders 的数组：值为 subHeader 索引x8
         */
        public int[] subHeaderKeys;
        /**
         * uint16[]
         * <p> 仅 Format=2
         * <p> subHeader 子标头的可变长度数组
         * <p> 其结构为 uint16[][4]{ {uint16,uint16,int16,uint16}, ... }
         */
        public int[] subHeaders;
        /**
         * uint16   segCount x2
         * <p> 仅 Format=4
         * <p> seg段计数乘以 2。这是因为每个段用两个字节表示，所以这个值是实际段数的两倍。
         */
        public int segCountX2;
        /**
         * uint16
         * <p> 仅 Format=4
         * <p> 小于或等于段数的最大二次幂，再乘以 2。这是为二分查找优化搜索过程。
         */
        public int searchRange;
        /**
         * uint16
         * <p> 仅 Format=4
         * <p> 等于 log2(searchRange/2)，这是最大二次幂的对数。
         */
        public int entrySelector;
        /**
         * uint16
         * <p> 仅 Format=4
         * <p> segCount * 2 - searchRange 用于调整搜索范围的偏移。
         */
        public int rangeShift;
        /**
         * uint16[segCount]
         * <p> 仅 Format=4
         * <p> 每个段的结束字符码，最后一个是 0xFFFF，表示 Unicode 范围的结束。
         */
        public int[] endCode;
        /**
         * uint16
         * <p> 仅 Format=4
         * <p> 固定设置为 0，用于填充保留位以保持数据对齐。
         */
        public int reservedPad;
        /**
         * uint16[segCount]
         * <p> 仅 Format=4
         * <p> 每个段的起始字符码。
         */
        public int[] startCode;
        /**
         * int16[segCount]
         * <p> 仅 Format=4
         * <p> 用于计算字形索引的偏移值。该值被加到从 startCode 到 endCode 的所有字符码上，得到相应的字形索引。
         */
        public int[] idDelta;
        /**
         * uint16[segCount]
         * <p> 仅 Format=4
         * <p> 偏移到 glyphIdArray 中的起始位置，如果没有额外的字形索引映射，则为 0。
         */
        public int[] idRangeOffsets;
        /**
         * uint16
         * <p> 仅 Format=6
         * <p> 子范围的第一个字符代码。这是连续字符代码范围的起始点。
         */
        public int firstCode;
        /**
         * uint16
         * <p> 仅 Format=6
         * <p> 子范围中字符代码的数量。这表示从 firstCode 开始，连续多少个字符代码被包含
         */
        public int entryCount;
        /**
         * 字形索引数组
         * <p> Format=0 为 bye[256]数组
         * <p> Format>0 为 uint16[] 数组
         * <p> Format>12 为 uint32[] 数组
         * <p> @url <a href="https://learn.microsoft.com/zh-cn/typography/opentype/spec/cmap#language">Microsoft cmap文档</a>
         */
        public int[] glyphIdArray;
    }

    /**
     * 字形轮廓数据表
     */
    public static class GlyfLayout {
        /**
         * int16    非负值为简单字形的轮廓数,负值表示为复合字形
         */
        public short numberOfContours;
        /**
         * int16    Minimum x for coordinate data.
         */
        public short xMin;
        /**
         * int16    Minimum y for coordinate data.
         */
        public short yMin;
        /**
         * int16    Maximum x for coordinate data.
         */
        public short xMax;
        /**
         * int16    Maximum y for coordinate data.
         */
        public short yMax;
        /**
         * 简单字形数据
         */
        public GlyphTableBySimple glyphSimple;
        /**
         * 复合字形数据
         */
        public GlyphTableComponent[] glyphComponent;
    }

    /**
     * 简单字形数据表
     */
    public static class GlyphTableBySimple {
        /**
         * uint16[numberOfContours]
         */
        int[] endPtsOfContours;
        /**
         * uint16
         */
        int instructionLength;
        /**
         * uint8[instructionLength]
         */
        int[] instructions;
        /**
         * uint8[variable]
         * <p> bit0: 该点位于曲线上
         * <p> bit1: < 1:xCoordinate为uint8 >
         * <p> bit2: < 1:yCoordinate为uint8 >
         * <p> bit3: < 1:下一个uint8为此条目之后插入的附加逻辑标志条目的数量 >
         * <p> bit4: < bit1=1时表示符号[1.正,0.负]; bit1=0时[1.x坐标重复一次,0.x坐标读为int16] >
         * <p> bit5: < bit2=1时表示符号[1.正,0.负]; bit2=0时[1.y坐标重复一次,0.y坐标读为int16] >
         * <p> bit6: 字形描述中的轮廓可能会重叠
         * <p> bit7: 保留位,无意义
         */
        int[] flags;
        /**
         * uint8[]  when(flags&0x02==0x02)
         * int16[]  when(flags&0x12==0x00)
         */
        int[] xCoordinates;
        /**
         * uint8[]  when(flags&0x04==0x02)
         * int16[]  when(flags&0x24==0x00)
         */
        int[] yCoordinates;
    }

    /**
     * 复合字形数据表
     */
    private static class GlyphTableComponent {
        /**
         * uint16
         * <p> bit0: < 1:argument是16bit，0:argument是8bit >
         * <p> bit1: < 1:argument是有符号值，0:argument是无符号值 >
         * <p> bit3: 该组件有一个缩放比例，否则比例为1.0
         * <p> bit5: 表示在此字形之后还有字形
         */
        int flags;
        /**
         * uint16
         */
        int glyphIndex;
        /**
         * x-offset
         * <p>  uint8 when flags&0x03==0
         * <p>   int8 when flags&0x03==1
         * <p> uint16 when flags&0x03==2
         * <p>  int16 when flags&0x03==3
         */
        int argument1;
        /**
         * y-offset
         * <p>  uint8 when flags&0x03==0
         * <p>   int8 when flags&0x03==1
         * <p> uint16 when flags&0x03==2
         * <p>  int16 when flags&0x03==3
         */
        int argument2;
        /**
         * uint16
         * <p> 值类型为 F2DOT14 的组件缩放X比例值
         */
        float xScale;
        /**
         * uint16
         * <p> 值类型为 F2DOT14 的2x2变换矩阵01值
         */
        float scale01;
        /**
         * uint16
         * <p> 值类型为 F2DOT14 的2x2变换矩阵10值
         */
        float scale10;
        /**
         * uint16
         * <p> 值类型为 F2DOT14 的组件缩放Y比例值
         */
        float yScale;
    }

    static class BufferReader {
        private final ByteBuffer byteBuffer;

        public BufferReader(byte[] buffer, int index) {
            this.byteBuffer = ByteBuffer.wrap(buffer);
            this.byteBuffer.order(ByteOrder.BIG_ENDIAN); // 设置为大端模式
            this.byteBuffer.position(index); // 设置起始索引
        }

        public ByteBuffer getByteBuffer() {
            return byteBuffer;
        }

        public void position(int index) {
            byteBuffer.position(index); // 设置起始索引
        }

        public int position() {
            return byteBuffer.position();
        }

        public long ReadUInt64() {
            return byteBuffer.getLong();
        }

        public int ReadUInt32() {
            return byteBuffer.getInt();
        }

        public int ReadInt32() {
            return byteBuffer.getInt();
        }

        public int ReadUInt16() {
            return byteBuffer.getShort() & 0xFFFF;
        }

        public short ReadInt16() {
            return byteBuffer.getShort();
        }

        public short ReadUInt8() {
            return (short) (byteBuffer.get() & 0xFF);
        }

        public byte ReadInt8() {
            return byteBuffer.get();
        }

        // 读取 4 字节的 ASCII 字符串
        public String ReadString4() {
            return new String(this.ReadByteArray(4));
        }

        public byte[] ReadByteArray(int len) {
            assert len >= 0;
            byte[] result = new byte[len];
            byteBuffer.get(result);
            return result;
        }

        public int[] ReadUInt8Array(int len) {
            assert len >= 0;
            var result = new int[len];
            for (int i = 0; i < len; ++i) result[i] = byteBuffer.get() & 0xFF;
            return result;
        }

        public int[] ReadInt16Array(int len) {
            assert len >= 0;
            var result = new int[len];
            for (int i = 0; i < len; ++i) result[i] = byteBuffer.getShort();
            return result;
        }

        public int[] ReadUInt16Array(int len) {
            assert len >= 0;
            var result = new int[len];
            for (int i = 0; i < len; ++i) result[i] = byteBuffer.getShort() & 0xFFFF;
            return result;
        }

        public int[] ReadInt32Array(int len) {
            assert len >= 0;
            var result = new int[len];
            for (int i = 0; i < len; ++i) result[i] = byteBuffer.getInt();
            return result;
        }

        // Presuming this method is added to your existing BufferReader class
// static class BufferReader {
//     private final ByteBuffer byteBuffer;
//     //... other existing methods...

        /**
         * 正确实现UIntBase128读取
         */
        public long ReadUIntBase128() throws IOException {
            long result = 0;
            int count = 0;

            while (true) {
                if (count >= 5) {
                    throw new IOException("UIntBase128 sequence too long (max 5 bytes)");
                }

                if (byteBuffer.remaining() == 0) {
                    throw new IOException("Unexpected end of data while reading UIntBase128");
                }

                byte b = byteBuffer.get();
                count++;

                // 检查是否超出32位范围
                if (count == 1 && b == (byte) 0x80) {
                    throw new IOException("Invalid UIntBase128: first byte cannot be 0x80");
                }

                // 将7位值添加到结果
                result = (result << 7) | (b & 0x7F);

                // 检查高位是否设置（表示还有更多字节）
                if ((b & 0x80) == 0) {
                    // 最终检查是否超出32位范围
                    if (result > 0xFFFFFFFFL) {
                        throw new IOException("UIntBase128 value exceeds 32-bit range: " + result);
                    }
                    return result;
                }
            }
        }

// } // End of BufferReader class
    }

    /**
     * CFF表头部
     */
    private static class CFFHeader {
        public int majorVersion;
        public int minorVersion;
        public int headerSize;
        public int offSize;
    }

    /**
     * CFF索引表结构
     */
    private static class CFFIndex {
        public int count;
        public int offSize;
        public int[] offsets;
        public byte[] data;
    }

    /**
     * CFF字形数据
     */
    public static class CFFGlyph {
        public byte[] charString;
    }

    private boolean isCFF = false;

    /**
     * 基于opentype.js实现的CFF解析
     */
    private void readCFFTable(byte[] buffer) {
        var dataTable = directorys.get("CFF ");
        if (dataTable == null) {
            Log.d(TAG, "No CFF table found");
            return;
        }

        isCFF = true;
        int glyphCount = maxp.numGlyphs;
        glyfArray = new GlyfLayout[glyphCount];

        try {
            // 创建解析器
            CFFParser parser = new CFFParser(buffer, dataTable.offset);
            // 解析CFF头部
            parser.parseHeader();
            // 解析索引表
            parser.parseIndexes();
            // 解析字形
            for (int glyphId = 0; glyphId < glyphCount; glyphId++) {
                if (glyphId < parser.charStrings.size()) {
                    GlyfLayout glyph = parser.parseGlyph(glyphId);
                    glyfArray[glyphId] = glyph;
                } else {
                    // 创建空字形
                    glyfArray[glyphId] = createEmptyGlyph();
                }
            }
            // 解析完成后释放资源
            parser.clear();
        } catch (Exception e) {
            Log.e(TAG, "CFF parsing error", e);
            // 创建默认字形
            for (int i = 0; i < glyphCount; i++) {
                glyfArray[i] = createEmptyGlyph();
            }
        }
    }

    private final Header fileHeader = new Header();
    private final HashMap<String, Directory> directorys = new HashMap<>();
    private final NameLayout name = new NameLayout();
    private final HeadLayout head = new HeadLayout();
    private final MaxpLayout maxp = new MaxpLayout();
    private final CmapLayout Cmap = new CmapLayout();
    private final int[][] pps = new int[][]{{3, 10}, {0, 4}, {3, 1}, {1, 0}, {0, 3}, {0, 1}};

    private void readNameTable(byte[] buffer) {
        var dataTable = directorys.get("name");
        assert dataTable != null;
        var reader = new BufferReader(buffer, dataTable.offset);
        name.format = reader.ReadUInt16();
        name.count = reader.ReadUInt16();
        name.stringOffset = reader.ReadUInt16();
        name.records = new NameRecord[name.count];

        for (int i = 0; i < name.count; ++i) {
            NameRecord record = new NameRecord();
            record.platformID = reader.ReadUInt16();
            record.encodingID = reader.ReadUInt16();
            record.languageID = reader.ReadUInt16();
            record.nameID = reader.ReadUInt16();
            record.length = reader.ReadUInt16();
            record.offset = reader.ReadUInt16();
            name.records[i] = record;
        }
    }

    private void readHeadTable(byte[] buffer) {
        var dataTable = directorys.get("head");
        assert dataTable != null;
        var reader = new BufferReader(buffer, dataTable.offset);
        head.majorVersion = reader.ReadUInt16();
        head.minorVersion = reader.ReadUInt16();
        head.fontRevision = reader.ReadUInt32();
        head.checkSumAdjustment = reader.ReadUInt32();
        head.magicNumber = reader.ReadUInt32();
        head.flags = reader.ReadUInt16();
        head.unitsPerEm = reader.ReadUInt16();
        head.created = reader.ReadUInt64();
        head.modified = reader.ReadUInt64();
        head.xMin = reader.ReadInt16();
        head.yMin = reader.ReadInt16();
        head.xMax = reader.ReadInt16();
        head.yMax = reader.ReadInt16();
        head.macStyle = reader.ReadUInt16();
        head.lowestRecPPEM = reader.ReadUInt16();
        head.fontDirectionHint = reader.ReadInt16();
        head.indexToLocFormat = reader.ReadInt16();
        head.glyphDataFormat = reader.ReadInt16();
    }

    /**
     * glyfId到glyphData的索引
     * <p> 根据定义，索引零指向“丢失的字符”。
     * <p> loca.length = maxp.numGlyphs + 1;
     */
    private int[] loca;

    private void readLocaTable(byte[] buffer) {
        var dataTable = directorys.get("loca");
        assert dataTable != null;
        var reader = new BufferReader(buffer, dataTable.offset);
        if (head.indexToLocFormat == 0) {
            loca = reader.ReadUInt16Array(dataTable.length / 2);
            // 当loca表数据长度为Uint16时,需要翻倍
            for (var i = 0; i < loca.length; i++) loca[i] *= 2;
        } else {
            loca = reader.ReadInt32Array(dataTable.length / 4);
        }
    }

    // 计算cmap子表优先级
    private int getCmapPriority(int platformID, int encodingID) {
        if (platformID == 3 && encodingID == 10) return 100; // Unicode Full
        if (platformID == 3 && encodingID == 1) return 90;  // Unicode BMP
        if (platformID == 0 && encodingID == 4) return 80;  // Unicode 2.0+
        if (platformID == 0 && encodingID == 3) return 70;  // Unicode 2.0
        if (platformID == 0 && encodingID == 1) return 60;  // Unicode 1.1
        return platformID * 10 + encodingID; // 默认排序
    }

    private void readCmapTable(byte[] buffer) {
        var dataTable = directorys.get("cmap");
        assert dataTable != null;
        var reader = new BufferReader(buffer, dataTable.offset);
        Cmap.version = reader.ReadUInt16();
        Cmap.numTables = reader.ReadUInt16();

        // 改为数组
        Cmap.records = new CmapRecord[Cmap.numTables];

        for (int i = 0; i < Cmap.numTables; ++i) {
            CmapRecord record = new CmapRecord();
            record.platformID = reader.ReadUInt16();
            record.encodingID = reader.ReadUInt16();
            record.offset = reader.ReadUInt32();
            Cmap.records[i] = record;
        }

        // 修复排序：使用Arrays.sort并正确实现比较器
        Arrays.sort(Cmap.records, (a, b) -> {
            int priorityA = getCmapPriority(a.platformID, a.encodingID);
            int priorityB = getCmapPriority(b.platformID, b.encodingID);
            // 降序排列：高优先级在前
            return Integer.compare(priorityB, priorityA);
        });

        // TODO: Implement cmap subtable selection logic here if desired,
        // e.g., prioritize Windows Unicode Full Repertoire (PID=3, EID=10, Format 12)
        // or Windows Unicode BMP (PID=3, EID=1, Format 4).
        // For now, it processes all found and supported subtables.

        for (var formatTable : Cmap.records) {
            int fmtOffset = formatTable.offset;
            if (Cmap.tables.containsKey(fmtOffset)) continue;
            reader.position(dataTable.offset + fmtOffset);

            CmapFormat f = new CmapFormat();
            f.format = reader.ReadUInt16();
            f.length = reader.ReadUInt16();
            f.language = reader.ReadUInt16();
            switch (f.format) {
                case 0: {
                    f.glyphIdArray = reader.ReadUInt8Array(f.length - 6);
                    // 记录 unicode->glyphId 映射表
                    int unicodeInclusive = 0;
                    int unicodeExclusive = f.glyphIdArray.length;
                    for (; unicodeInclusive < unicodeExclusive; unicodeInclusive++) {
                        if (f.glyphIdArray[unicodeInclusive] == 0) continue; // 排除轮廓索引为0的Unicode
                        unicodeToGlyphId.put(unicodeInclusive, f.glyphIdArray[unicodeInclusive]);
                    }
                    f.glyphIdArray = null;
                    break;
                }
                case 4: {
                    f.segCountX2 = reader.ReadUInt16();
                    int segCount = f.segCountX2 / 2;
                    f.searchRange = reader.ReadUInt16();
                    f.entrySelector = reader.ReadUInt16();
                    f.rangeShift = reader.ReadUInt16();
                    f.endCode = reader.ReadUInt16Array(segCount);
                    f.reservedPad = reader.ReadUInt16();
                    f.startCode = reader.ReadUInt16Array(segCount);
                    f.idDelta = reader.ReadInt16Array(segCount);
                    f.idRangeOffsets = reader.ReadUInt16Array(segCount);
                    // 一个包含字形索引的数组，其长度是任意的，取决于映射的复杂性和字体中的字符数量。
                    // Calculation of glyphIdArrayLength was correct:  (f.length - 16 - (segCount * 8)) / 2;
                    int glyphIdArrayLength = (f.length - 16 - segCount * 8) / 2;
//                    int glyphIdArrayLength = (f.length - (14 + (segCount * 8) + 2)) / 2; // 14 for header, 8*segCount for arrays, 2 for reservedPad
                    if (glyphIdArrayLength < 0)
                        glyphIdArrayLength = 0; // Handle potential malformed length
                    f.glyphIdArray = reader.ReadUInt16Array(glyphIdArrayLength);

                    // 记录 unicode->glyphId 映射表
                    for (int segmentIndex = 0; segmentIndex < segCount; segmentIndex++) {
                        int unicodeInclusive = f.startCode[segmentIndex];
                        int unicodeExclusive = f.endCode[segmentIndex];

                        if (unicodeInclusive == 0xFFFF || unicodeExclusive == 0xFFFF && segmentIndex < segCount - 1) {
                            // Skip dummy segment if not last
                            if (unicodeInclusive == 0xFFFF && unicodeExclusive == 0xFFFF && f.idDelta[segmentIndex] == 1 && f.idRangeOffsets[segmentIndex] == 0)
                                continue;
                        }

                        int currentIdDelta = f.idDelta[segmentIndex];
                        int currentIdRangeOffset = f.idRangeOffsets[segmentIndex];
                        for (int unicode = unicodeInclusive; unicode <= unicodeExclusive; unicode++) {
                            if (unicode == 0xFFFF)
                                break; // End of valid Unicode range for this segment type

                            int glyphId = 0;
                            if (currentIdRangeOffset == 0) {
                                glyphId = (unicode + currentIdDelta) & 0xFFFF;
                            } else {
                                // gIndex calculation is a common pattern for this structure
                                int gIndex = (currentIdRangeOffset / 2) + (unicode - unicodeInclusive) + (segmentIndex - segCount);

                                if (gIndex >= 0 && gIndex < glyphIdArrayLength) {
                                    int rawGlyphId = f.glyphIdArray[gIndex];
                                    if (rawGlyphId != 0) { // <<<< ****** CORRECTED LOGIC ******
                                        glyphId = (rawGlyphId + currentIdDelta) & 0xFFFF;
                                    }
                                }
                            }
                            if (glyphId == 0 && unicode != 0)
                                continue; // Keep mapping for U+0000 if it maps to.notdef
                            // but skip other unicodes that map to.notdef
                            // unless we want to explicitly store them.
                            // Original code: if (glyphId == 0) continue;
                            // This change is subtle: if a non-zero unicode maps to glyph 0,
                            // we might want to record that for completeness.
                            // However, for typical use (finding a displayable glyph), skipping is fine.
                            // Let's stick to original: if glyphId is 0, it's.notdef, don't store.
                            if (glyphId == 0) continue;

                            unicodeToGlyphId.put(unicode, glyphId);
                        }
                    }
                    // 立即释放大数组
                    f.endCode = null;
                    f.startCode = null;
                    f.idDelta = null;
                    f.idRangeOffsets = null;
                    f.glyphIdArray = null;
                    break;
                }
                case 6: {
                    f.firstCode = reader.ReadUInt16();
                    f.entryCount = reader.ReadUInt16();
                    // 范围内字符代码的字形索引值数组。
                    f.glyphIdArray = reader.ReadUInt16Array(f.entryCount);

                    // 记录 unicode->glyphId 映射表
                    int unicodeIndex = f.firstCode;
                    int unicodeCount = f.entryCount;
                    for (int gIndex = 0; gIndex < unicodeCount; gIndex++) {
                        // It's possible f.glyphIdArray[gIndex] is 0.
                        // If so, that unicode maps to.notdef.
                        if (f.glyphIdArray[gIndex] == 0 && unicodeIndex != 0)
                            continue; // Similar to Format 4, skip if maps to.notdef unless U+0000
                        unicodeToGlyphId.put(unicodeIndex, f.glyphIdArray[gIndex]);
                        unicodeIndex++;
                    }
                    f.glyphIdArray = null;
                    break;
                }
                case 12: {
                    // Format12: 32位分段覆盖
                    reader.ReadUInt16(); // 跳过保留字段
                    f.length = reader.ReadUInt32();
                    f.language = reader.ReadUInt32();
                    long numGroups = reader.ReadUInt32();

                    for (long i = 0; i < numGroups; i++) {
                        long startCharCode = reader.ReadUInt32();
                        long endCharCode = reader.ReadUInt32();
                        long startGlyphID = reader.ReadUInt32();

                        // 处理32位Unicode范围
                        for (long charCode = startCharCode; charCode <= endCharCode; charCode++) {
                            int unicode = (int) charCode;
                            int glyphId = (int) (startGlyphID + (charCode - startCharCode));

                            // 仅存储新映射或覆盖无效映射
                            if (!unicodeToGlyphId.containsKey(unicode) || unicodeToGlyphId.get(unicode) == 0) {
                                unicodeToGlyphId.put(unicode, glyphId);
                            }
                        }
                    }
                    break;
                }
                default:
                    break;
            }
            Cmap.tables.put(fmtOffset, f);
        }
    }

    private void readMaxpTable(byte[] buffer) {
        var dataTable = directorys.get("maxp");
        assert dataTable != null;
        var reader = new BufferReader(buffer, dataTable.offset);
        maxp.version = reader.ReadUInt32();
        maxp.numGlyphs = reader.ReadUInt16();
        maxp.maxPoints = reader.ReadUInt16();
        maxp.maxContours = reader.ReadUInt16();
        maxp.maxCompositePoints = reader.ReadUInt16();
        maxp.maxCompositeContours = reader.ReadUInt16();
        maxp.maxZones = reader.ReadUInt16();
        maxp.maxTwilightPoints = reader.ReadUInt16();
        maxp.maxStorage = reader.ReadUInt16();
        maxp.maxFunctionDefs = reader.ReadUInt16();
        maxp.maxInstructionDefs = reader.ReadUInt16();
        maxp.maxStackElements = reader.ReadUInt16();
        maxp.maxSizeOfInstructions = reader.ReadUInt16();
        maxp.maxComponentElements = reader.ReadUInt16();
        maxp.maxComponentDepth = reader.ReadUInt16();
    }

    /**
     * 字形轮廓表 数组
     */
    private GlyfLayout[] glyfArray;

    private int[] unicodeArray;

    private void readGlyfTable(byte[] buffer) {
        // 如果是CFF字体(OTF)，不需要读取glyf表
        if (isCFF) return;

        var dataTable = directorys.get("glyf");
        if (dataTable == null) return;

        int glyfCount = maxp.numGlyphs;
        glyfArray = new GlyfLayout[glyfCount];  // 创建字形容器

        // 重用reader避免重复创建
        BufferReader reader = new BufferReader(buffer, 0);

        for (int index = 0; index < glyfCount; index++) {
            if (loca[index] == loca[index + 1]) {
                // 创建空对象而不是null
                glyfArray[index] = createEmptyGlyph();
                continue;
            }

            int offset = dataTable.offset + loca[index];
            // 读GlyphHeaders
            var glyph = new GlyfLayout();
            reader.position(offset);
            glyph.numberOfContours = reader.ReadInt16();
            if (glyph.numberOfContours > maxp.maxContours)
                continue; // 如果字形轮廓数大于非复合字形中包含的最大轮廓数，则说明该字形无效。
            glyph.xMin = reader.ReadInt16();
            glyph.yMin = reader.ReadInt16();
            glyph.xMax = reader.ReadInt16();
            glyph.yMax = reader.ReadInt16();

            // 轮廓数为0时，不需要解析轮廓数据
            if (glyph.numberOfContours == 0) continue;
            // 读Glyph轮廓数据
            if (glyph.numberOfContours > 0) {
                // 简单轮廓
                glyph.glyphSimple = new GlyphTableBySimple();
                glyph.glyphSimple.endPtsOfContours = reader.ReadUInt16Array(glyph.numberOfContours);
                glyph.glyphSimple.instructionLength = reader.ReadUInt16();
                glyph.glyphSimple.instructions = reader.ReadUInt8Array(glyph.glyphSimple.instructionLength);
                int flagLength = glyph.glyphSimple.endPtsOfContours[glyph.glyphSimple.endPtsOfContours.length - 1] + 1;
                // 获取轮廓点描述标志
                glyph.glyphSimple.flags = new int[flagLength];
                for (int n = 0; n < flagLength; ++n) {
                    var glyphSimpleFlag = reader.ReadUInt8();
                    glyph.glyphSimple.flags[n] = glyphSimpleFlag;
                    if ((glyphSimpleFlag & 0x08) == 0x08) {
                        for (int m = reader.ReadUInt8(); m > 0; --m) {
                            glyph.glyphSimple.flags[++n] = glyphSimpleFlag;
                        }
                    }
                }
                // 获取轮廓点描述x轴相对值
                glyph.glyphSimple.xCoordinates = new int[flagLength];
                for (int n = 0; n < flagLength; ++n) {
                    switch (glyph.glyphSimple.flags[n] & 0x12) {
                        case 0x02:
                            glyph.glyphSimple.xCoordinates[n] = -1 * reader.ReadUInt8();
                            break;
                        case 0x12:
                            glyph.glyphSimple.xCoordinates[n] = reader.ReadUInt8();
                            break;
                        case 0x10:
                            glyph.glyphSimple.xCoordinates[n] = 0;  // 点位数据重复上一次数据，那么相对数据变化量就是0
                            break;
                        case 0x00:
                            glyph.glyphSimple.xCoordinates[n] = reader.ReadInt16();
                            break;
                    }
                }
                // 获取轮廓点描述y轴相对值
                glyph.glyphSimple.yCoordinates = new int[flagLength];
                for (int n = 0; n < flagLength; ++n) {
                    switch (glyph.glyphSimple.flags[n] & 0x24) {
                        case 0x04:
                            glyph.glyphSimple.yCoordinates[n] = -1 * reader.ReadUInt8();
                            break;
                        case 0x24:
                            glyph.glyphSimple.yCoordinates[n] = reader.ReadUInt8();
                            break;
                        case 0x20:
                            glyph.glyphSimple.yCoordinates[n] = 0;  // 点位数据重复上一次数据，那么相对数据变化量就是0
                            break;
                        case 0x00:
                            glyph.glyphSimple.yCoordinates[n] = reader.ReadInt16();
                            break;
                    }
                }
            } else {
                // 复合轮廓
                ArrayList<GlyphTableComponent> components = new ArrayList<>(5);
                while (true) {
                    GlyphTableComponent comp = new GlyphTableComponent();
                    comp.flags = reader.ReadUInt16();
                    comp.glyphIndex = reader.ReadUInt16();
                    switch (comp.flags & 0b11) {
                        case 0b00:
                            comp.argument1 = reader.ReadUInt8();
                            comp.argument2 = reader.ReadUInt8();
                            break;
                        case 0b10:
                            comp.argument1 = reader.ReadInt8();
                            comp.argument2 = reader.ReadInt8();
                            break;
                        case 0b01:
                            comp.argument1 = reader.ReadUInt16();
                            comp.argument2 = reader.ReadUInt16();
                            break;
                        case 0b11:
                            comp.argument1 = reader.ReadInt16();
                            comp.argument2 = reader.ReadInt16();
                            break;
                    }
                    switch (comp.flags & 0b11001000) {
                        case 0b00001000:
                            // 有单一比例
                            comp.yScale = comp.xScale = ((float) reader.ReadUInt16()) / 16384.0f;
                            break;
                        case 0b01000000:
                            // 有X和Y的独立比例
                            comp.xScale = ((float) reader.ReadUInt16()) / 16384.0f;
                            comp.yScale = ((float) reader.ReadUInt16()) / 16384.0f;
                            break;
                        case 0b10000000:
                            // 有2x2变换矩阵
                            comp.xScale = ((float) reader.ReadUInt16()) / 16384.0f;
                            comp.scale01 = ((float) reader.ReadUInt16()) / 16384.0f;
                            comp.scale10 = ((float) reader.ReadUInt16()) / 16384.0f;
                            comp.yScale = ((float) reader.ReadUInt16()) / 16384.0f;
                            break;
                    }
                    components.add(comp);
                    if ((comp.flags & 0x20) == 0) break;
                }
                // 转换为数组存储
                glyph.glyphComponent = components.toArray(new GlyphTableComponent[0]);
            }
            glyfArray[index] = glyph;
        }
    }

    /**
     * 使用轮廓索引值获取轮廓数据
     *
     * @param glyfId 轮廓索引
     * @return 轮廓数据
     */
    public String getGlyfById(int glyfId) {
        var glyph = glyfArray[glyfId];
        if (glyph == null) return null;    // 过滤不存在的字体轮廓

        StringBuilder sb = new StringBuilder();
        if (glyph.numberOfContours >= 0 && glyph.glyphSimple != null) {
            int dataCount = glyph.glyphSimple.flags.length;
            for (int i = 0; i < dataCount; i++) {
                if (i > 0) sb.append('|');
                sb.append(glyph.glyphSimple.xCoordinates[i])
                        .append(',')
                        .append(glyph.glyphSimple.yCoordinates[i]);
            }
        } else if (glyph.numberOfContours < 0 && glyph.glyphComponent != null) {
            sb.append('[');
            for (int i = 0; i < glyph.glyphComponent.length; i++) {
                if (i > 0) sb.append(',');
                GlyphTableComponent g = glyph.glyphComponent[i];
                sb.append('{')
                        .append("flags:").append(g.flags).append(',')
                        .append("glyphIndex:").append(g.glyphIndex).append(',')
                        .append("arg1:").append(g.argument1).append(',')
                        .append("arg2:").append(g.argument2).append(',')
                        .append("xScale:").append(g.xScale).append(',')
                        .append("scale01:").append(g.scale01).append(',')
                        .append("scale10:").append(g.scale10).append(',')
                        .append("yScale:").append(g.yScale).append('}');
            }
            sb.append(']');
        }
        return sb.toString();
    }

    private String fontType = "ttf";

    private void initFont(byte[] buffer) {
        // 原有构造函数中的初始化逻辑
        var fontReader = new BufferReader(buffer, 0);
//        Log.i("QueryTTF", "读文件头"); // 获取文件头
        fileHeader.sfntVersion = fontReader.ReadUInt32();
        fileHeader.numTables = fontReader.ReadUInt16();
        fileHeader.searchRange = fontReader.ReadUInt16();
        fileHeader.entrySelector = fontReader.ReadUInt16();
        fileHeader.rangeShift = fontReader.ReadUInt16();
        // 获取目录
        for (int i = 0; i < fileHeader.numTables; ++i) {
            Directory d = new Directory();
            d.tableTag = new String(fontReader.ReadByteArray(4), StandardCharsets.US_ASCII);
            d.checkSum = fontReader.ReadUInt32();
            d.offset = fontReader.ReadUInt32();
            d.length = fontReader.ReadUInt32();
            directorys.put(d.tableTag, d);
        }

//        Log.i("QueryTTF", "解析表 name"); // 字体信息,包含版权、名称、作者等...
        readNameTable(buffer);
        uniqueFontName = getUniqueFontName(buffer);
//        readUniqueName(buffer);
//        Log.i("QueryTTF", "解析表 head"); // 获取 head.indexToLocFormat
        readHeadTable(buffer);
//        Log.i("QueryTTF", "解析表 cmap"); // Unicode编码->轮廓索引 对照表
        readCmapTable(buffer);
//        Log.i("QueryTTF", "解析表 maxp"); // 获取 maxp.numGlyphs 字体轮廓数量
        readMaxpTable(buffer);
        // 检查字体类型（TTF或OTF）

        int glyfArrayLength;

        if (directorys.containsKey("CFF ")) {
            readCFFTable(buffer); // 解析CFF表
            fontType = "otf";
//            glyfArrayLength = cffGlyphs.length;
        } else {
//        Log.i("QueryTTF", "解析表 loca"); // 轮廓数据偏移地址表
            readLocaTable(buffer);
//        Log.i("QueryTTF", "解析表 glyf"); // 字体轮廓数据表,需要解析loca,maxp表后计算
            readGlyfTable(buffer);
//            glyfArrayLength = glyfArray.length;
            fontType = "ttf";
        }

        buildGlyphMapping();
//        Log.i("QueryTTF", "字体处理完成");
        // 其他初始化...
    }

    public String getFontType() {
        return fontType;
    }

    private String uniqueFontName;

    public String getUniqueFontName() {
        return uniqueFontName;
    }

    public String getUniqueFontName(byte[] buffer) {
        // 1. 优先获取 PostScript 名称 (nameID=6)
        String psName = getPostScriptName(buffer);
        if (psName != null && !psName.trim().isEmpty()) {
            return psName;
        }

        // 2. 获取完整字体名 (nameID=4)
        String fullName = getFullFontName(buffer);
        if (fullName != null && !fullName.trim().isEmpty()) {
            return fullName;
        }

        // 3. 获取家族名 (nameID=1)
        String family = getFontFamily(buffer);
        if (family != null && !family.trim().isEmpty()) {
            return family;
        }

        // 4. 使用文件特征作为后备
        return "Font-" + getFileChecksum(buffer);
    }

    private String getPostScriptName(byte[] buffer) {
        return getNameById(buffer, 6);
    }

    private String getFullFontName(byte[] buffer) {
        return getNameById(buffer, 4);
    }

    private String getFontFamily(byte[] buffer) {
        return getNameById(buffer, 1);
    }

    private String getNameById(byte[] buffer, int nameId) {
        for (NameRecord record : name.records) {
            if (record.nameID == nameId) {
                return decodeNameString(buffer, record);
            }
        }
        return null;
    }

    // 修正后的解码方法，正确处理偏移量和长度
    private String decodeNameString(byte[] buffer, NameRecord record) {
        Directory nameTable = directorys.get("name");
        if (nameTable == null) return "";

        int stringBase = nameTable.offset + name.stringOffset;
        int start = stringBase + record.offset;
        int length = record.length;

        // 边界检查
        if (start < 0 || start >= buffer.length) return "";
        if (start + length > buffer.length) {
            length = buffer.length - start; // 调整长度防止越界
        }
        if (length <= 0) return "";

        // 根据平台和编码选择解码方式
        switch (record.platformID) {
            case 0: // Unicode 平台
                return decodeUnicodeString(buffer, start, length, record.encodingID);
            case 1: // Mac 平台
                return decodeMacString(buffer, start, length, record.encodingID);
            case 3: // Windows 平台
                return decodeWindowsString(buffer, start, length, record.encodingID);
            default:
                return safeDecode(buffer, start, length);
        }
    }

    // 所有解码方法都添加偏移量和长度参数
    private String decodeUnicodeString(byte[] data, int offset, int length, int encodingId) {
        // 只解码指定范围的数据
        return new String(data, offset, length, StandardCharsets.UTF_16BE);
    }

    private String decodeWindowsString(byte[] data, int offset, int length, int encodingId) {
        switch (encodingId) {
            case 0: // Symbol 编码
                return decodeSymbolEncoding(data, offset, length);
            case 1: // Unicode BMP
            case 10: // Unicode 全字符集
                return new String(data, offset, length, StandardCharsets.UTF_16BE);
            default:
                return safeDecode(data, offset, length);
        }
    }

    private String decodeSymbolEncoding(byte[] data, int offset, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = offset; i < offset + length; i++) {
            int code = data[i] & 0xFF;
            // 映射到Unicode私有使用区 (U+F000 - U+F0FF)
            sb.append((char) (0xF000 + code));
        }
        return sb.toString();
    }

    private String decodeMacString(byte[] data, int offset, int length, int encodingId) {
        // 创建指定范围的子数组进行解码
        byte[] subData = Arrays.copyOfRange(data, offset, offset + length);
        return decodeMacString(subData, encodingId);
    }

    private String safeDecode(byte[] data, int offset, int length) {
        // 创建指定范围的子数组进行安全解码
        byte[] subData = Arrays.copyOfRange(data, offset, offset + length);
        return safeDecode(subData);
    }

    private String decodeMacString(byte[] data, int encodingId) {
        // Mac 平台处理
        switch (encodingId) {
            case 0: // Roman
                return decodeMacRoman(data);

            case 1: // Japanese
                return decodeShiftJIS(data);

            case 2: // Chinese Traditional
                return decodeBig5(data);

            case 3: // Korean
                return decodeEUCKR(data);

            case 4: // Arabic
            case 5: // Hebrew
            case 6: // Greek
            case 7: // Russian
                return decodeMacCyrillic(data);

            default:
                return safeDecode(data);
        }
    }

    private String decodeMacRoman(byte[] data) {
        // MacRoman 到 Unicode 的映射表 (0x80-0xFF)
        final char[] MAC_ROMAN_MAPPING = {
                '\u00C4', '\u00C5', '\u00C7', '\u00C9', '\u00D1', '\u00D6', '\u00DC', '\u00E1',
                '\u00E0', '\u00E2', '\u00E4', '\u00E3', '\u00E5', '\u00E7', '\u00E9', '\u00E8',
                '\u00EA', '\u00EB', '\u00ED', '\u00EC', '\u00EE', '\u00EF', '\u00F1', '\u00F3',
                '\u00F2', '\u00F4', '\u00F6', '\u00F5', '\u00FA', '\u00F9', '\u00FB', '\u00FC',
                '\u2020', '\u00B0', '\u00A2', '\u00A3', '\u00A7', '\u2022', '\u00B6', '\u00DF',
                '\u00AE', '\u00A9', '\u2122', '\u00B4', '\u00A8', '\u2260', '\u00C6', '\u00D8',
                '\u221E', '\u00B1', '\u2264', '\u2265', '\u00A5', '\u00B5', '\u2202', '\u2211',
                '\u220F', '\u03C0', '\u222B', '\u00AA', '\u00BA', '\u03A9', '\u00E6', '\u00F8',
                '\u00BF', '\u00A1', '\u00AC', '\u221A', '\u0192', '\u2248', '\u2206', '\u00AB',
                '\u00BB', '\u2026', '\u00A0', '\u00C0', '\u00C3', '\u00D5', '\u0152', '\u0153',
                '\u2013', '\u2014', '\u201C', '\u201D', '\u2018', '\u2019', '\u00F7', '\u25CA',
                '\u00FF', '\u0178', '\u2044', '\u20AC', '\u2039', '\u203A', '\uFB01', '\uFB02',
                '\u2021', '\u00B7', '\u201A', '\u201E', '\u2030', '\u00C2', '\u00CA', '\u00C1',
                '\u00CB', '\u00C8', '\u00CD', '\u00CE', '\u00CF', '\u00CC', '\u00D3', '\u00D4',
                '\uF8FF', '\u00D2', '\u00DA', '\u00DB', '\u00D9', '\u0131', '\u02C6', '\u02DC',
                '\u00AF', '\u02D8', '\u02D9', '\u02DA', '\u00B8', '\u02DD', '\u02DB', '\u02C7'
        };

        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            int unsigned = b & 0xFF;
            if (unsigned < 0x80) {
                // ASCII 字符直接添加
                sb.append((char) unsigned);
            } else if (unsigned >= 0x80 && unsigned <= 0xFF) {
                // 映射到 Unicode
                sb.append(MAC_ROMAN_MAPPING[unsigned - 0x80]);
            }
        }
        return sb.toString();
    }

    private String decodeShiftJIS(byte[] data) {
        try {
            return new String(data, "Shift_JIS");
        } catch (UnsupportedEncodingException e) {
            // 回退到安全解码
            return safeDecode(data);
        }
    }

    private String decodeBig5(byte[] data) {
        try {
            return new String(data, "Big5");
        } catch (UnsupportedEncodingException e) {
            return safeDecode(data);
        }
    }

    private String decodeEUCKR(byte[] data) {
        try {
            return new String(data, "EUC-KR");
        } catch (UnsupportedEncodingException e) {
            return safeDecode(data);
        }
    }

    private String decodeMacCyrillic(byte[] data) {
        try {
            // 尝试标准Cyrillic编码
            return new String(data, "MacCyrillic");
        } catch (UnsupportedEncodingException e) {
            // 尝试其他斯拉夫语编码
            try {
                return new String(data, "ISO-8859-5");
            } catch (UnsupportedEncodingException ex) {
                return safeDecode(data);
            }
        }
    }

    // 使用静态常量
    private static final String[] SAFE_DECODE_ENCODINGS = {
            "UTF-8", "UTF-16BE", "ISO-8859-1", "Windows-1252",
            "GBK", "Big5", "Shift_JIS", "EUC-KR"
    };

    private String safeDecode(byte[] data) {
        // 尝试多种常见编码
        for (String encoding : SAFE_DECODE_ENCODINGS) {
            try {
                String result = new String(data, encoding);
                if (isValidString(result)) {
                    return result;
                }
            } catch (Exception ignored) {
            }
        }

        // 最终回退：ASCII 安全模式
        return decodeSafeAscii(data);
    }

    private boolean isValidString(String str) {
        if (str == null || str.trim().isEmpty()) return false;

        // 检查是否包含可识别字符
        int letterCount = 0;
        for (char c : str.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                letterCount++;
            }
        }

        // 至少需要30%的字母数字字符才认为是有效字符串
        return letterCount > (str.length() * 0.3);
    }

    private String decodeSafeAscii(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            int value = b & 0xFF;
            if (value >= 32 && value <= 126) { // 可打印ASCII范围
                sb.append((char) value);
            } else if (value > 126) {
                // 尝试保留基本字符
                char c = (char) value;
                if (Character.isLetterOrDigit(c) || ".-_".indexOf(c) >= 0) {
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private String getFileChecksum(byte[] buffer) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(buffer);
            return bytesToHex(digest).substring(0, 6); // 取前6位作为短ID
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static final char[] HEX_ARRAY = "0123456789abcdef".toCharArray();

    // 优化9：减少字符串操作
    private static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hexChars[i * 2] = HEX_ARRAY[v >>> 4];
            hexChars[i * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars, 0, 12); // 只取前6个字符
    }

    /**
     * 构建字形映射 - 统一处理TTF和CFF
     */
    private void buildGlyphMapping() {
        int numGlyphs = maxp.numGlyphs;
        unicodeArray = new int[numGlyphs];

        // 释放不再需要的大对象
        Cmap.tables.clear();
        loca = null;

        for (var item : unicodeToGlyphId.entrySet()) {
            int unicode = item.getKey();
            int glyfId = item.getValue();

            if (glyfId >= numGlyphs || glyfId < 0) continue;

            String glyfString = getGlyfById(glyfId);
            if (glyfString == null) continue;

            unicodeToGlyph.put(unicode, glyfString);
            glyphToUnicode.put(glyfString, unicode);
            unicodeArray[glyfId] = unicode;
        }

        // 释放cmap原始数据
        Cmap.records = null;
    }

    // 暴露重新初始化的方法
    public void reinit(byte[] newBuffer) {
        initFont(newBuffer);
    }

    /**
     * 构造函数
     *
     * @param buffer 传入TTF字体二进制数组
     */
    public QueryTTF(final byte[] buffer) {
        if (buffer.length <= 0) {
            return;
        }
        initFont(buffer);
    }

    public final HashMap<Integer, String> unicodeToGlyph = new HashMap<>();
    public final HashMap<String, Integer> glyphToUnicode = new HashMap<>();
    public final HashMap<Integer, Integer> unicodeToGlyphId = new HashMap<>();

    /**
     * 使用 Unicode 值获查询廓索引
     *
     * @param unicode 传入 Unicode 值
     * @return 轮廓索引
     */
    public int getGlyfIdByUnicode(int unicode) {
        var result = unicodeToGlyphId.get(unicode);
        if (result == null) return 0; // 如果找不到Unicode对应的轮廓索引，就返回默认值0
        return result;
    }

    /**
     * 使用 Unicode 值查询轮廓数据
     *
     * @param unicode 传入 Unicode 值
     * @return 轮廓数据
     */
    public String getGlyfByUnicode(int unicode) {
        return unicodeToGlyph.get(unicode);
    }

    /**
     * 使用轮廓数据反查 Unicode 值
     *
     * @param glyph 传入轮廓数据
     * @return Unicode
     */
    public int getUnicodeByGlyf(String glyph) {
        if (null == glyph)
            return 0;
        var result = glyphToUnicode.get(glyph);
        if (result == null) {
            GlyphMatcher glyphMatcher = new GlyphMatcher(glyfArray, unicodeArray);
            ConditionVariable wait = new ConditionVariable();
            AtomicInteger unicodeAtom = new AtomicInteger(-1);
            glyphMatcher.findClosestAsync(glyph, new GlyphMatcher.MatchCallback() {
                @Override
                public void onResult(int unicode) {
                    unicodeAtom.set(unicode);
                    wait.open();
                }

                @Override
                public void onError(String message) {
                    unicodeAtom.set(0);
                    wait.open();
                }
            });
            if (unicodeAtom.get() < 0) {
                wait.block();
            }
            wait.close();
            return Math.max(unicodeAtom.get(), 0);
        } // 如果轮廓数据找不到对应的Unicode，就返回默认值0
        return result;
    }

    /**
     * Unicode 空白字符判断
     *
     * @param unicode 字符的 Unicode 值
     * @return true:是空白字符; false:非空白字符
     */
    public boolean isBlankUnicode(int unicode) {
        return switch (unicode) {
            case 0x0009,    // 水平制表符 (Horizontal Tab)
                 0x0020,    // 空格 (Space)
                 0x00A0,    // 不中断空格 (No-Break Space)
                 0x2002,    // En空格 (En Space)
                 0x2003,    // Em空格 (Em Space)
                 0x2007,    // 刚性空格 (Figure Space)
                 0x200A,    // 发音修饰字母的连字符 (Hair Space)
                 0x200B,    // 零宽空格 (Zero Width Space)
                 0x200C,    // 零宽不连字 (Zero Width Non-Joiner)
                 0x200D,    // 零宽连字 (Zero Width Joiner)
                 0x202F,    // 狭窄不中断空格 (Narrow No-Break Space)
                 0x205F     // 中等数学空格 (Medium Mathematical Space)
                    -> true;
            default -> false;
        };
    }


    /**
     * 存储CFF解析结果的数据结构
     */
    private static class CFFGlyphData {
        public int contourCount = 0;
        public int pointCount = 0;
        public int[] endPtsOfContours = null;
        public int[] flags = null;
        public int[] xCoordinates = null;
        public int[] yCoordinates = null;
        public int xMin = 0;
        public int yMin = 0;
        public int xMax = 0;
        public int yMax = 0;

        public void reset() {
            contourCount = 0;
            pointCount = 0;
            endPtsOfContours = null;
            flags = null;
            xCoordinates = null;
            yCoordinates = null;
            xMin = 0;
            yMin = 0;
            xMax = 0;
            yMax = 0;
        }
    }

    private static GlyfLayout createEmptyGlyph() {
        GlyfLayout glyph = new GlyfLayout();
        glyph.numberOfContours = 0;
        glyph.xMin = 0;
        glyph.yMin = 0;
        glyph.xMax = 0;
        glyph.yMax = 0;
        return glyph;
    }

    /**
     * 基于opentype.js的CFF解析器
     */
    private static class CFFParser {
        private final byte[] buffer;
        private final int baseOffset;
        private int privateDictOffset;
        private int position;

        // 解析结果
        private int version;
        private int headerSize;
        private int offSize;

        private final List<CFFIndex> indexes = new ArrayList<>();
        private List<byte[]> charStrings = new ArrayList<>();
        // 添加全局子程序列表
        private List<byte[]> globalSubrs = new ArrayList<>();
        // 添加本地子程序列表（用于CID字体）
        private List<byte[]> localSubrs = new ArrayList<>();

        // 优化：可重用的数据结构
        private final List<Integer> reusableStack = new ArrayList<>();
        private final List<Integer> reusableXCoords = new ArrayList<>();
        private final List<Integer> reusableYCoords = new ArrayList<>();
        private final List<Boolean> reusableFlags = new ArrayList<>();
        private final List<Integer> reusableEndPts = new ArrayList<>();
        private final Deque<ByteBuffer> callStack = new ArrayDeque<>();

        public CFFParser(byte[] buffer, int baseOffset) {
            this.buffer = buffer;
            this.baseOffset = baseOffset;
            this.position = baseOffset;
        }

        public void parseHeader() {
            // 读取头部
            version = readUInt8();
            int minorVersion = readUInt8();
            headerSize = readUInt8();
            offSize = readUInt8();

            // 跳过剩余头部
            position = baseOffset + headerSize;
        }

        public void parseIndexes() {
            // 解析索引表
            indexes.add(parseIndex());  // Name INDEX
            indexes.add(parseIndex());  // Top DICT INDEX
            indexes.add(parseIndex());  // String INDEX
            indexes.add(parseIndex());  // Global Subr INDEX

            // 保存全局子程序
            globalSubrs = getIndexData(indexes.get(3));

            // 从Top DICT中获取CharStrings位置
            int charStringsOffset = getCharStringsOffset(indexes.get(1));
            if (charStringsOffset < 0) {
                throw new RuntimeException("CharStrings offset not found");
            }

            // 尝试获取Private DICT（用于本地子程序）
            privateDictOffset = getPrivateDictOffset(indexes.get(1));
            if (privateDictOffset > 0) {
                position = baseOffset + privateDictOffset;
                parsePrivateDict();
            }

            // 定位并解析CharStrings INDEX
            position = baseOffset + charStringsOffset;
            CFFIndex charStringsIndex = parseIndex();
            charStrings = getIndexData(charStringsIndex);
        }

        private void parsePrivateDict() {
            // 读取Private DICT长度
            int privateDictLength = readUInt16();
            if (privateDictLength <= 0) return;

            // 读取Private DICT数据
            byte[] privateDictData = new byte[privateDictLength];
            System.arraycopy(buffer, position, privateDictData, 0, privateDictLength);
            position += privateDictLength;

            // 从Private DICT中获取本地子程序偏移
            int subrOffset = parseDictForSubrOffset(privateDictData);
            if (subrOffset > 0) {
                int savePos = position;
                position = baseOffset + privateDictOffset + subrOffset;
                CFFIndex localSubrIndex = parseIndex();
                localSubrs = getIndexData(localSubrIndex);
                position = savePos;
            }
        }

        private int getPrivateDictOffset(CFFIndex topDictIndex) {
            if (topDictIndex.count == 0 || topDictIndex.data == null) {
                return -1;
            }

            byte[] dictData = topDictIndex.data;
            ByteBuffer reader = ByteBuffer.wrap(dictData);
            LinkedList<Integer> stack = new LinkedList<>();

            while (reader.hasRemaining()) {
                int b0 = reader.get() & 0xFF;

                if (b0 >= 32) { // 数字
                    if (b0 <= 246) {
                        stack.push(b0 - 139);
                    } else if (b0 <= 250) {
                        if (!reader.hasRemaining()) break;
                        int b1 = reader.get() & 0xFF;
                        stack.push((b0 - 247) * 256 + b1 + 108);
                    } else if (b0 <= 254) {
                        if (!reader.hasRemaining()) break;
                        int b1 = reader.get() & 0xFF;
                        stack.push(-(b0 - 251) * 256 - b1 - 108);
                    }
                } else { // 操作符
                    if (b0 == 28) {
                        if (reader.remaining() < 2) break;
                        stack.push((int) reader.getShort());
                    } else if (b0 == 29) {
                        if (reader.remaining() < 4) break;
                        stack.push(reader.getInt());
                    } else if (b0 == 30) {
                        skipRealNumber(reader);
                    } else if (b0 == 12) {
                        if (!reader.hasRemaining()) break;
                        int b1 = reader.get() & 0xFF;
                        if (b1 == 18) { // Private (18)
                            if (stack.size() >= 2) {
                                int size = stack.removeLast();
                                return stack.removeLast();
                            }
                        }
                        stack.clear();
                    } else if (b0 == 18) { // Private
                        if (stack.size() >= 2) {
                            int size = stack.removeLast();
                            return stack.removeLast();
                        }
                    } else {
                        stack.clear();
                    }
                }
            }
            return -1;
        }

        private int parseDictForSubrOffset(byte[] dictData) {
            ByteBuffer reader = ByteBuffer.wrap(dictData);
            LinkedList<Integer> stack = new LinkedList<>();

            while (reader.hasRemaining()) {
                int b0 = reader.get() & 0xFF;

                // 数字处理
                if (b0 >= 32) {
                    if (b0 <= 246) {  // 单字节数字
                        stack.push(b0 - 139);
                    } else if (b0 <= 250) {  // 两字节正数
                        if (!reader.hasRemaining()) break;
                        int b1 = reader.get() & 0xFF;
                        stack.push((b0 - 247) * 256 + b1 + 108);
                    } else if (b0 <= 254) {  // 两字节负数
                        if (!reader.hasRemaining()) break;
                        int b1 = reader.get() & 0xFF;
                        stack.push(-(b0 - 251) * 256 - b1 - 108);
                    }
                    // 255 是操作符，不在这里处理
                } else {  // 操作符
                    if (b0 == 28) {  // 16位整数
                        if (reader.remaining() < 2) break;
                        short value = reader.getShort();
                        stack.push((int) value);
                    } else if (b0 == 29) {  // 32位整数
                        if (reader.remaining() < 4) break;
                        int value = reader.getInt();
                        stack.push(value);
                    } else if (b0 == 30) {  // 实数 - 跳过
                        skipRealNumber(reader);
                    } else if (b0 == 19) {  // Subr (19)
                        if (!stack.isEmpty()) {
                            return stack.removeLast();
                        }
                    } else {  // 其他操作符 - 清空栈
                        stack.clear();
                    }
                }
            }
            return -1;  // 未找到Subr偏移量
        }

        private void skipRealNumber(ByteBuffer reader) {
            while (reader.hasRemaining()) {
                int b = reader.get() & 0xFF;
                int nibble1 = (b >> 4) & 0x0F;
                int nibble2 = b & 0x0F;

                // 遇到0xF结束
                if (nibble1 == 0x0F || nibble2 == 0x0F) break;
            }
        }

        public GlyfLayout parseGlyph(int glyphId) {
            byte[] charString = charStrings.get(glyphId);
            if (charString == null || charString.length == 0) {
                return createEmptyGlyph();
            }

            try {
                // 重置可重用数据结构
                reusableStack.clear();
                reusableXCoords.clear();
                reusableYCoords.clear();
                reusableFlags.clear();
                reusableEndPts.clear();
                callStack.clear();

                return parseCharString(charString);
            } catch (Exception e) {
                Log.e(TAG, "Error parsing glyph " + glyphId, e);
                return createEmptyGlyph();
            }
        }

        // 计算子程序索引的bias值
        private int calculateBias(int subrCount) {
            if (subrCount < 1240) return 107;
            if (subrCount < 33900) return 1131;
            return 32768;
        }

        private GlyfLayout parseCharString(byte[] charString) {
            // 使用整数栈和整数坐标
            int x = 0, y = 0;
            int contourCount = 0;
            boolean hasStartPoint = false;

            // 子程序调用栈
            ByteBuffer reader = ByteBuffer.wrap(charString);
            reader.order(ByteOrder.BIG_ENDIAN);

            // 计算子程序调用的bias
            int globalBias = calculateBias(globalSubrs.size());
            int localBias = calculateBias(localSubrs.size());

            // 优化循环结构：确保读取前有可用数据
            while (true) {
                // 检查当前reader是否有数据，若无则尝试弹出调用栈
                if (!reader.hasRemaining()) {
                    if (!callStack.isEmpty()) {
                        reader = callStack.pop();
                        continue; // 检查新reader
                    } else {
                        break; // 无数据且栈空则退出
                    }
                }

                int op = reader.get() & 0xFF;

                // 数字处理
                if (op >= 32 && op <= 246) {
                    reusableStack.add(op - 139);
                } else if (op >= 247 && op <= 250) {
                    int op2 = reader.get() & 0xFF;
                    reusableStack.add((op - 247) * 256 + op2 + 108);
                } else if (op >= 251 && op <= 254) {
                    int op2 = reader.get() & 0xFF;
                    reusableStack.add(-(op - 251) * 256 - op2 - 108);
                } else if (op == 28) {
                    int b1 = reader.get() & 0xFF;
                    int b2 = reader.get() & 0xFF;
                    reusableStack.add((b1 << 8) | b2);
                } else if (op == 255) {
                    // 在读取前检查剩余字节
                    if (reader.remaining() < 4) break;
                    reusableStack.add(reader.getInt());
                } else {
                    // 命令处理
                    switch (op) {
                        // 水平/垂直线段
                        // 修改stem指令处理：仅清空当前指令关联的参数，保留其他栈内容
                        case 1:  // hstem
                        case 3:  // vstem
                        case 18: // hstemhm
                        case 23: // stem指令忽略
                            // 根据参数数量弹出（每个stem对需要2个参数）
                            int stemCount = reusableStack.size() / 2;
                            for (int i = 0; i < stemCount * 2; i++) {
                                popInt(reusableStack);
                            }
                            continue;

                        case 4: // vmoveto
                            if (!reusableStack.isEmpty()) {
                                y += popInt(reusableStack);
                                addPoint(x, y, reusableXCoords, reusableYCoords, reusableFlags, true);
                            }
                            break;

                        // 线段绘制
                        case 5: // rlineto
                            while (reusableStack.size() >= 2) {
                                int dy = popInt(reusableStack);
                                int dx = popInt(reusableStack);
                                x += dx;
                                y += dy;
                                addPoint(x, y, reusableXCoords, reusableYCoords, reusableFlags, true);
                            }
                            continue;

                        case 6: // hlineto
                            while (!reusableStack.isEmpty()) {
                                x += popInt(reusableStack);
                                addPoint(x, y, reusableXCoords, reusableYCoords, reusableFlags, true);
                                if (!reusableStack.isEmpty()) {
                                    y += popInt(reusableStack);
                                    addPoint(x, y, reusableXCoords, reusableYCoords, reusableFlags, true);
                                }
                            }
                            break;

                        case 7: // vlineto
                            while (!reusableStack.isEmpty()) {
                                y += popInt(reusableStack);
                                addPoint(x, y, reusableXCoords, reusableYCoords, reusableFlags, true);
                                if (!reusableStack.isEmpty()) {
                                    x += popInt(reusableStack);
                                    addPoint(x, y, reusableXCoords, reusableYCoords, reusableFlags, true);
                                }
                            }
                            break;

                        // 贝塞尔曲线
                        case 8: // rrcurveto
                            while (reusableStack.size() >= 6) {
                                int dy1 = popInt(reusableStack);
                                int dx1 = popInt(reusableStack);
                                int dy2 = popInt(reusableStack);
                                int dx2 = popInt(reusableStack);
                                int dy3 = popInt(reusableStack);
                                int dx3 = popInt(reusableStack);

                                int cx1 = x + dx1;
                                int cy1 = y + dy1;
                                int cx2 = cx1 + dx2;
                                int cy2 = cy1 + dy2;
                                x = cx2 + dx3;
                                y = cy2 + dy3;

                                addPoint(cx1, cy1, reusableXCoords, reusableYCoords, reusableFlags, false);
                                addPoint(cx2, cy2, reusableXCoords, reusableYCoords, reusableFlags, false);
                                addPoint(x, y, reusableXCoords, reusableYCoords, reusableFlags, true);
                            }
                            continue;

                            // 子程序调用
                        case 10: // callsubr
                            if (!reusableStack.isEmpty()) {
                                int subrValue = popInt(reusableStack);
                                int subrNum = subrValue + localBias;
                                if (subrNum >= 0 && subrNum < localSubrs.size()) {
                                    byte[] subrBytes = localSubrs.get(subrNum);
                                    if (subrBytes != null && subrBytes.length > 0) {
                                        callStack.push(reader);
                                        reader = ByteBuffer.wrap(subrBytes);
                                        reader.order(ByteOrder.BIG_ENDIAN);
                                        continue; // 立即跳转子程序
                                    }
                                }
                            }
                            break;

                        case 11: // return
                            if (!callStack.isEmpty()) {
                                reader = callStack.pop();
                            }
                            break;
                        // 转义操作符
                        case 12:
                            if (reader.hasRemaining()) {
                                int subOp = reader.get() & 0xFF;
                                // 添加对callsubr转义形式的处理
                                if (subOp == 10) { // 12_10 = callsubr
                                    if (!reusableStack.isEmpty()) {
                                        int subrNum = popInt(reusableStack) + localBias;
                                        if (subrNum >= 0 && subrNum < localSubrs.size()) {
                                            byte[] subr = localSubrs.get(subrNum);
                                            if (subr != null && subr.length > 0) {
                                                callStack.push(reader);
                                                reader = ByteBuffer.wrap(subr);
                                                continue;
                                            }
                                        }
                                    }
                                } else {
                                    handleEscapedOp(subOp, reusableStack, reusableXCoords, reusableYCoords, reusableFlags);
                                }
                            }
                            break;

                        // 轮廓结束
                        case 14: // endchar
                            if (!reusableXCoords.isEmpty()) {
                                reusableEndPts.add(reusableXCoords.size() - 1);
                                contourCount++;
                            }
                            // 不退出循环，继续处理可能存在的后续指令
                            continue;

                            // 添加 hsbw 指令处理
                        case 19: // hsbw (horizontal side bearing and width)
                            if (reusableStack.size() >= 2) {
                                popInt(reusableStack); // 忽略宽度 (wx)
                                popInt(reusableStack); // 忽略水平侧边距 (wy)
                            }
                            continue;

                        case 21: // rmoveto
                            if (reusableStack.size() >= 2) {
                                int dy = popInt(reusableStack);
                                int dx = popInt(reusableStack);
                                x += dx;
                                y += dy;
                                addPoint(x, y, reusableXCoords, reusableYCoords, reusableFlags, true);
                                // 标记已存在起始点
                            } else if (reusableStack.size() == 1) {
                                // 处理单参数情况（Type 2允许）
                                x += popInt(reusableStack);
                                addPoint(x, y, reusableXCoords, reusableYCoords, reusableFlags, true);
                            }
                            continue;

                        case 22: // hmoveto
                            if (!reusableStack.isEmpty()) {
                                x += popInt(reusableStack);
                                addPoint(x, y, reusableXCoords, reusableYCoords, reusableFlags, true);
                            }
                            break;

                        // 在 parseCharString 方法的 switch (op) 块中添加以下 case 分支
                        case 24: // rcurveline (绘制曲线后跟直线)
                            while (reusableStack.size() >= 8) {
                                // 处理曲线部分 (6个参数)
                                int dy1 = popInt(reusableStack);
                                int dx1 = popInt(reusableStack);
                                int dy2 = popInt(reusableStack);
                                int dx2 = popInt(reusableStack);
                                int dy3 = popInt(reusableStack);
                                int dx3 = popInt(reusableStack);

                                int cx1 = x + dx1;
                                int cy1 = y + dy1;
                                int cx2 = cx1 + dx2;
                                int cy2 = cy1 + dy2;
                                x = cx2 + dx3;
                                y = cy2 + dy3;

                                addPoint(cx1, cy1, reusableXCoords, reusableYCoords, reusableFlags, false);
                                addPoint(cx2, cy2, reusableXCoords, reusableYCoords, reusableFlags, false);
                                addPoint(x, y, reusableXCoords, reusableYCoords, reusableFlags, true);
                            }

                            // 处理直线部分 (2个参数)
                            if (reusableStack.size() >= 2) {
                                int dy = popInt(reusableStack);
                                int dx = popInt(reusableStack);
                                x += dx;
                                y += dy;
                                addPoint(x, y, reusableXCoords, reusableYCoords, reusableFlags, true);
                            }
                            continue;

                        case 25: // rlinecurve (绘制直线后跟曲线)
                            // 处理直线部分 (需要至少2个参数，且保留最后6个参数给曲线)
                            while (reusableStack.size() > 6) {
                                int dy = popInt(reusableStack);
                                int dx = popInt(reusableStack);
                                x += dx;
                                y += dy;
                                addPoint(x, y, reusableXCoords, reusableYCoords, reusableFlags, true);
                            }

                            // 处理曲线部分 (6个参数)
                            if (reusableStack.size() == 6) {
                                int dy1 = popInt(reusableStack);
                                int dx1 = popInt(reusableStack);
                                int dy2 = popInt(reusableStack);
                                int dx2 = popInt(reusableStack);
                                int dy3 = popInt(reusableStack);
                                int dx3 = popInt(reusableStack);

                                int cx1 = x + dx1;
                                int cy1 = y + dy1;
                                int cx2 = cx1 + dx2;
                                int cy2 = cy1 + dy2;
                                x = cx2 + dx3;
                                y = cy2 + dy3;

                                addPoint(cx1, cy1, reusableXCoords, reusableYCoords, reusableFlags, false);
                                addPoint(cx2, cy2, reusableXCoords, reusableYCoords, reusableFlags, false);
                                addPoint(x, y, reusableXCoords, reusableYCoords, reusableFlags, true);
                            }
                            continue;

                        case 27: // hhcurveto
                            while (!reusableStack.isEmpty()) {
                                int dx1 = popInt(reusableStack);
                                int dx2 = popInt(reusableStack);
                                int dy2 = reusableStack.size() % 2 == 1 ? popInt(reusableStack) : 0;
                                int dx3 = popInt(reusableStack);

                                addPoint(x + dx1, y, reusableXCoords, reusableYCoords, reusableFlags, false);
                                addPoint(x + dx1 + dx2, y + dy2, reusableXCoords, reusableYCoords, reusableFlags, false);

                                x += dx1 + dx2 + dx3;
                                y += dy2;
                                addPoint(x, y, reusableXCoords, reusableYCoords, reusableFlags, true);
                            }
                            break;

                        case 29: // callgsubr
                            if (!reusableStack.isEmpty()) {
                                int subrValue = popInt(reusableStack);
                                int subrNum = subrValue + globalBias;
                                if (subrNum >= 0 && subrNum < globalSubrs.size()) {
                                    byte[] subrBytes = globalSubrs.get(subrNum);
                                    if (subrBytes != null && subrBytes.length > 0) {
                                        callStack.push(reader);
                                        reader = ByteBuffer.wrap(subrBytes);
                                        reader.order(ByteOrder.BIG_ENDIAN);
                                        continue;
                                    }
                                }
                            }
                            break;
                        default:
                            Log.w(TAG, "Unhandled opcode: " + op);
                            break;
                    }
                    reusableStack.clear();
                }
            }

            // 确保所有点集都被视为一个轮廓
            if (!reusableXCoords.isEmpty() && contourCount == 0) {
                reusableEndPts.add(reusableXCoords.size() - 1);
                contourCount++;
            }

            // 创建字形对象
            GlyfLayout glyph = new GlyfLayout();
            glyph.numberOfContours = (short) contourCount;

            if (contourCount > 0) {
                glyph.glyphSimple = new GlyphTableBySimple();

                // 将可重用列表转换为数组
                glyph.glyphSimple.endPtsOfContours = toIntArray(reusableEndPts);
                glyph.glyphSimple.xCoordinates = toIntArray(reusableXCoords);
                glyph.glyphSimple.yCoordinates = toIntArray(reusableYCoords);
                glyph.glyphSimple.flags = toFlagArray(reusableFlags);
                calculateBoundingBox(glyph);
            }

            return glyph;
        }

        // 辅助方法：将列表转换为int数组
        private int[] toIntArray(List<Integer> list) {
            int[] array = new int[list.size()];
            for (int i = 0; i < list.size(); i++) {
                array[i] = list.get(i);
            }
            return array;
        }

        // 辅助方法：将Boolean列表转换为int数组
        private int[] toFlagArray(List<Boolean> flags) {
            int[] array = new int[flags.size()];
            for (int i = 0; i < flags.size(); i++) {
                array[i] = flags.get(i) ? 1 : 0;
            }
            return array;
        }

        // 处理转义操作符
        private void handleEscapedOp(int op, List<Integer> stack,
                                     List<Integer> xCoords, List<Integer> yCoords,
                                     List<Boolean> flags) {
            switch (op) {
                case 34: // vmoveto (相对)
                    if (!stack.isEmpty()) {
                        int dy = popInt(stack);
                        addPoint(0, dy, xCoords, yCoords, flags, true);
                    }
                    break;

                case 35: // rlineto (相对)
                    while (stack.size() >= 2) {
                        int dx = popInt(stack);
                        int dy = popInt(stack);
                        addPoint(dx, dy, xCoords, yCoords, flags, true);
                    }
                    break;

                case 36: // hlineto (相对)
                    while (!stack.isEmpty()) {
                        int dx = popInt(stack);
                        addPoint(dx, 0, xCoords, yCoords, flags, true);
                    }
                    break;

                case 37: // rmoveto (相对)
                    if (stack.size() >= 2) {
                        int dx = popInt(stack);
                        int dy = popInt(stack);
                        addPoint(dx, dy, xCoords, yCoords, flags, true);
                    }
                    break;

                case 38: // rrcurveto (相对)
                    while (stack.size() >= 6) {
                        int dx1 = popInt(stack);
                        int dy1 = popInt(stack);
                        int dx2 = popInt(stack);
                        int dy2 = popInt(stack);
                        int dx3 = popInt(stack);
                        int dy3 = popInt(stack);

                        addPoint(dx1, dy1, xCoords, yCoords, flags, false);
                        addPoint(dx1 + dx2, dy1 + dy2, xCoords, yCoords, flags, false);
                        addPoint(dx1 + dx2 + dx3, dy1 + dy2 + dy3, xCoords, yCoords, flags, true);
                    }
                    break;

                default:
                    Log.w(TAG, "Unhandled escaped opcode: 12_" + op);
                    break;
            }
        }

        private void addPoint(int px, int py,
                              List<Integer> xCoords, List<Integer> yCoords,
                              List<Boolean> flags, boolean onCurve) {
            xCoords.add(px);
            yCoords.add(py);
            flags.add(onCurve);
        }

        // 整数弹出方法
        private int popInt(List<Integer> stack) {
            return stack.isEmpty() ? 0 : stack.remove(stack.size() - 1);
        }

        private void calculateBoundingBox(GlyfLayout glyph) {
            if (glyph.glyphSimple == null || glyph.glyphSimple.xCoordinates.length == 0) {
                return;
            }

            int minX = glyph.glyphSimple.xCoordinates[0];
            int minY = glyph.glyphSimple.yCoordinates[0];
            int maxX = minX;
            int maxY = minY;

            for (int i = 1; i < glyph.glyphSimple.xCoordinates.length; i++) {
                int x = glyph.glyphSimple.xCoordinates[i];
                int y = glyph.glyphSimple.yCoordinates[i];

                if (x < minX) minX = x;
                if (y < minY) minY = y;
                if (x > maxX) maxX = x;
                if (y > maxY) maxY = y;
            }

            glyph.xMin = (short) minX;
            glyph.yMin = (short) minY;
            glyph.xMax = (short) maxX;
            glyph.yMax = (short) maxY;
        }

        private int getCharStringsOffset(CFFIndex topDictIndex) {
            if (topDictIndex.count == 0 || topDictIndex.data == null) {
                return -1;
            }

            // 只取第一个Top DICT的数据（通常只有一个）
            byte[] dictData = topDictIndex.data;
            return parseDictForCharStringsOffset(dictData);
        }

        private int parseDictForCharStringsOffset(byte[] dictData) {
            ByteBuffer reader = ByteBuffer.wrap(dictData);
            LinkedList<Integer> stack = new LinkedList<>(); // 操作数栈
            int charStringsOffset = -1;

            while (reader.hasRemaining()) {
                int b0 = reader.get() & 0xFF;

                if (b0 >= 32) { // 数字类型
                    if (b0 <= 246) {
                        stack.push(b0 - 139);
                    } else if (b0 <= 250) {
                        if (!reader.hasRemaining()) break;
                        int b1 = reader.get() & 0xFF;
                        stack.push((b0 - 247) * 256 + b1 + 108);
                    } else if (b0 <= 254) {
                        if (!reader.hasRemaining()) break;
                        int b1 = reader.get() & 0xFF;
                        stack.push(-(b0 - 251) * 256 - b1 - 108);
                    }
                    // 255未定义，跳过
                } else { // 操作符类型 (0-31)
                    if (b0 == 28) { // 2字节整数
                        if (reader.remaining() < 2) break;
                        stack.push((int) reader.getShort());
                    } else if (b0 == 29) { // 4字节整数
                        if (reader.remaining() < 4) break;
                        stack.push(reader.getInt());
                    } else if (b0 == 30) { // 实数类型，跳过
                        skipRealNumber(reader);
                    } else if (b0 == 12) { // 双字节操作符
                        if (!reader.hasRemaining()) break;
                        int b1 = reader.get() & 0xFF;
                        // 非目标操作符清空栈
                        stack.clear();
                    } else { // 单字节操作符
                        if (b0 == 17) { // 目标操作符: CharStrings (17)
                            if (!stack.isEmpty()) {
                                charStringsOffset = stack.getLast(); // 获取最后一个操作数
                            }
                        }
                        stack.clear(); // 处理完操作符后清空栈
                    }
                }
            }
            return charStringsOffset;
        }

        private CFFIndex parseIndex() {
            CFFIndex index = new CFFIndex();

            if (position + 2 > buffer.length) {
                throw new RuntimeException("Index header out of range");
            }

            // 读取索引头
            index.count = readUInt16();

            if (index.count == 0) {
                return index;
            }

            index.offSize = readUInt8();

            // 读取偏移量数组
            int offsetLength = (index.count + 1) * index.offSize;
            if (position + offsetLength > buffer.length) {
                throw new RuntimeException("Offset array out of range");
            }

            index.offsets = new int[index.count + 1];
            for (int i = 0; i <= index.count; i++) {
                index.offsets[i] = readOffset(index.offSize);
            }

            // 读取数据
            int dataStart = position;
            int dataEnd = position + (index.offsets[index.count] - 1);
            if (dataEnd > buffer.length) {
                throw new RuntimeException("Index data out of range");
            }

            int dataLength = dataEnd - position;
            index.data = new byte[dataLength];
            System.arraycopy(buffer, position, index.data, 0, dataLength);
            position = dataEnd;

            return index;
        }

        private List<byte[]> getIndexData(CFFIndex index) {
            List<byte[]> dataList = new ArrayList<>();

            for (int i = 0; i < index.count; i++) {
                int start = index.offsets[i] - 1;
                int end = index.offsets[i + 1] - 1;

                if (start < 0 || end < start || end > index.data.length) {
                    dataList.add(new byte[0]);
                } else {
                    byte[] data = Arrays.copyOfRange(index.data, start, end);
                    dataList.add(data);
                }
            }

            return dataList;
        }

        private int readOffset(int offSize) {
            int offset = 0;
            for (int i = 0; i < offSize; i++) {
                offset = (offset << 8) | (readUInt8() & 0xFF);
            }
            return offset;
        }

        private int readUInt8() {
            if (position >= buffer.length) {
                throw new RuntimeException("Read beyond buffer length");
            }
            return buffer[position++] & 0xFF;
        }

        private int readUInt16() {
            if (position + 1 >= buffer.length) {
                throw new RuntimeException("Read beyond buffer length");
            }
            int value = (buffer[position] & 0xFF) << 8 | (buffer[position + 1] & 0xFF);
            position += 2;
            return value;
        }

        // 在 CFFParser 中添加清理方法
        public void clear() {
            indexes.clear();
            charStrings.clear();
            globalSubrs.clear();
            localSubrs.clear();
            reusableStack.clear();
            reusableXCoords.clear();
            reusableYCoords.clear();
            reusableFlags.clear();
            reusableEndPts.clear();
            callStack.clear();
        }
    }
}
