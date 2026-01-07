package io.legado.app.model.analyzeRule;

import android.os.Build;
import android.util.LruCache;

import androidx.annotation.Keep;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.InflaterInputStream;

import org.brotli.dec.BrotliInputStream;

@Keep
public class QueryWOFF extends QueryTTF {
    private static final String TAG = "QueryWOFF";

    // 缓存机制：以字体特征值为键缓存解压后的 TTF 数据 [[4]][[5]][[9]]
    private static final int CACHE_SIZE = 1024 * 1024 * 10; // 10MB 缓存
    private static final LruCache<String, byte[]> fontCache = new LruCache<>(CACHE_SIZE);

    // 压缩类型常量（原 Zopfli 已替换为 ZLIB）
    private static final int COMPRESSION_NONE = 0;
    private static final int COMPRESSION_ZLIB = 1;  // 替代 Zopfli
    private static final int COMPRESSION_BROTLI = 2;

    /**
     * WOFF 文件头结构
     */
    private static class WOFFHeader {
        public String signature;
        public long flavor;
        public long length;
        public int numTables;
        public int reserved;
        public long totalSfntSize;
        public int majorVersion;
        public int minorVersion;
        public int metaOffset;
        public int metaLength;
        public int metaOrigLength;
        public int privOffset;
        public int privLength;
    }

    /**
     * 表目录结构
     */
    private static class WOFFTableDirectory {
        public String tag;
        public int offset;
        public int compLength;
        public int origLength;
        public int origChecksum;
        public int compressionType = COMPRESSION_NONE;
    }

    /**
     * 构造函数（带缓存机制）
     * @param buffer WOFF 字体二进制数组
     * @throws IOException 解压异常
     */
    public QueryWOFF(byte[] buffer) throws IOException {
        super(new byte[0]);  // 临时初始化
        String cacheKey = calculateCacheKey(buffer);
        // 检查缓存
        byte[] cachedData = fontCache.get(cacheKey);
        if (cachedData != null) {
            super.reinit(cachedData);
            return;
        }
        try {
            BufferReader reader = new BufferReader(buffer, 0);
            WOFFHeader woffHeader = readWOFFHeader(reader);
            // 验证字体格式（TTF 或 OTF）
            if (woffHeader.flavor != 0x00010000L && woffHeader.flavor != 0x4F54544FL) {
                throw new IOException("Unsupported font format");
            }
            // 读取表目录
            WOFFTableDirectory[] tables = readWOFFTables(reader, woffHeader);
            // 检测压缩类型（根据 WOFF 版本判断）
            detectCompressionType(woffHeader, tables);
            // 流式解压字体数据
            byte[] decompressed = streamDecompressFontData(buffer, tables, woffHeader);
            // 缓存解压结果
            fontCache.put(cacheKey, decompressed);
            // 调用父类构造函数处理解压后的 TTF 数据
            super.reinit(decompressed);
        } finally {
            // 显式释放内存
            buffer = null;
        }
    }

    /**
     * 计算字体缓存键（使用前 100 字节特征值）
     */
    private String calculateCacheKey(byte[] buffer) {
        int hash = 1;
        for (int i = 0; i < Math.min(100, buffer.length); i++) {
            hash = (hash * 31) + buffer[i];
        }
        return String.valueOf(hash);
    }

    /**
     * 检测压缩类型（根据 WOFF 版本和表数据）
     */
    private void detectCompressionType(WOFFHeader header, WOFFTableDirectory[] tables) {
        if (header.majorVersion == 0x0002 && header.minorVersion == 0x0000) {
            for (WOFFTableDirectory table : tables) {
                table.compressionType = COMPRESSION_BROTLI;
            }
        } else {
            for (WOFFTableDirectory table : tables) {
                table.compressionType = COMPRESSION_ZLIB;  // 替代 Zopfli
            }
        }
    }

    private byte[] streamDecompressFontData(byte[] buffer, WOFFTableDirectory[] tables, WOFFHeader header) throws IOException {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream((int) header.totalSfntSize)) {
            // 统计实际处理的表数量
            int actualNumTables = 0;
            for (WOFFTableDirectory table : tables) {
                if (isMetadataOrPrivate(table, header)) continue;
                actualNumTables++;
            }
            // 使用实际表数量生成 TTF 头
            writeEmptyFontHeader(outputStream, actualNumTables, header.flavor);
            int tableDataStart = 12 + 16 * actualNumTables;
            outputStream.write(new byte[tableDataStart - outputStream.size()]); // 填充至数据区

            ByteBuffer inputBuffer = ByteBuffer.wrap(buffer);
            inputBuffer.order(ByteOrder.BIG_ENDIAN);

            // 2. 解压并写入表数据，收集表信息
            List<TableInfo> tableInfos = new ArrayList<>();

            List<WOFFTableDirectory> sortedWoffTables = Arrays.asList(tables);
            Collections.sort(sortedWoffTables, Comparator.comparing(t -> t.tag));

            for (WOFFTableDirectory table : sortedWoffTables) {
                // 删除跳过逻辑：
                if (isMetadataOrPrivate(table, header)) continue;
                byte[] compData = new byte[table.compLength];
                inputBuffer.position(table.offset);
                inputBuffer.get(compData, 0, table.compLength);
                byte[] decompData = decompress(compData, table.compLength, table.origLength, table.compressionType);
                writeTableData(outputStream, table, decompData, tableInfos);
            }

            // 3. 回填目录项到预留区域
            writeTableDirectories(outputStream, tableInfos);
            byte[] result = outputStream.toByteArray();
            adjustHeadTable(result);
            return result;
//            return outputStream.toByteArray();
        }
    }

    /**
     * 修复字形问题的关键：调整head表校验和逻辑
     */
    private void adjustHeadTable(byte[] fontData) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(fontData).order(ByteOrder.BIG_ENDIAN);
        int numTables = buf.getShort(4) & 0xFFFF;

        // 1. 查找head表位置
        int headEntryOffset = -1;
        int headOffset = -1;
        for (int i = 0; i < numTables; i++) {
            int entryPos = 12 + i * 16;
            if (new String(fontData, entryPos, 4).equals("head")) {
                headEntryOffset = entryPos;
                headOffset = buf.getInt(entryPos + 8);
                break;
            }
        }
        if (headOffset == -1) throw new IOException("Missing head table");

        // 2. 将head表checksumAdjustment置0
        buf.position(headOffset + 8);
        buf.putInt(0);

        // 3. 计算head表数据的新校验和
        byte[] headTableData = Arrays.copyOfRange(fontData, headOffset, headOffset + 36);
        int newHeadChecksum = calculateChecksum(headTableData);

        // 4. 更新表目录中head表的校验和
        buf.position(headEntryOffset + 4);
        buf.putInt(newHeadChecksum);

        // 5. 计算整个文件的校验和
        long fileChecksum = calculateFileChecksum(fontData);

        // 6. 计算并写入adjustment
        int adjustment = 0xB1B0AFBA - (int) fileChecksum;
        buf.position(headOffset + 8);
        buf.putInt(adjustment);
    }

    private long calculateFileChecksum(byte[] data) {
        long sum = 0;
        int len = data.length;
        int paddedLen = (len + 3) & ~3; // 向上取整到4的倍数

        for (int i = 0; i < paddedLen; i += 4) {
            long value = 0;
            int bytesLeft = Math.min(4, len - i);
            for (int j = 0; j < bytesLeft; j++) {
                value = (value << 8) | (data[i + j] & 0xFF);
            }
            // 不足4字节时高位补0
            if (bytesLeft < 4) value <<= (8 * (4 - bytesLeft));
            sum = (sum + value) & 0xFFFFFFFFL;
        }
        return sum;
    }

    // 辅助方法：写入空文件头并预留目录空间
    private void writeEmptyFontHeader(ByteArrayOutputStream outputStream, int numTables, long flavor) throws IOException {
        // 计算 searchRange, entrySelector, rangeShift
        int maxPower2 = (int) Math.pow(2, (int) (Math.log(numTables) / Math.log(2)));
        int searchRange = maxPower2 * 16;
        int entrySelector = (int) (Math.log(maxPower2) / Math.log(2));
        int rangeShift = numTables * 16 - searchRange;

        // TTF 文件头 (12字节)
        byte[] header = ByteBuffer.allocate(12)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt((int) flavor)   // TTF版本 1.0
                .putShort((short) numTables)
                .putShort((short) searchRange)
                .putShort((short) entrySelector)
                .putShort((short) rangeShift)
                .array();
        outputStream.write(header);

        // 预留目录项空间（16字节/表 × 表数量）
        outputStream.write(new byte[16 * numTables]);
    }

    // 辅助方法：回填目录项
    private void writeTableDirectories(ByteArrayOutputStream outputStream, List<TableInfo> tableInfos) throws IOException {
        byte[] data = outputStream.toByteArray();
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

        // 目录项起始位置：文件头后（12字节）
        int directoryStart = 12;

        int expectedTables = (buffer.getShort(4) & 0xFFFF); // 从TTF头读取numTables

        if (tableInfos.size() != expectedTables) {
            throw new IOException("Table count mismatch. Expected: " + expectedTables + ", Actual: " + tableInfos.size());
        }

        for (int i = 0; i < tableInfos.size(); i++) {
            TableInfo info = tableInfos.get(i);
            buffer.position(directoryStart + i * 16);

            // 写入目录项（16字节）
            buffer.put(info.tag.getBytes(StandardCharsets.US_ASCII)); // 4字节 Tag
            buffer.putInt(info.checksum);                            // 4字节 Checksum
            buffer.putInt(info.offset);                             // 4字节 Offset
            buffer.putInt(info.length);                              // 4字节 Length
        }

        // 更新输出流
        outputStream.reset();
        outputStream.write(data);
    }

    private int calculateChecksum(byte[] data) {
        long sum = 0;
        int len = data.length;
        // 按4字节分组计算，不足部分补0
        for (int i = 0; i < len; i += 4) {
            long value = 0;
            int bytesLeft = Math.min(4, len - i);
            for (int j = 0; j < bytesLeft; j++) {
                value = (value << 8) | (data[i + j] & 0xFF);
            }
            // 不足4字节时高位补0
            if (bytesLeft < 4) value <<= (8 * (4 - bytesLeft));
            sum = (sum + value) & 0xFFFFFFFFL;
        }
        return (int) sum;
    }

//    private int calculateChecksum(byte[] data) {
//        // 1. 填充数据到4的倍数长度
//        int length = data.length;
//        int paddedLength = (length + 3) & ~3; // 等价于向上取整到最近的4的倍数
//        byte[] paddedData = Arrays.copyOf(data, paddedLength);
//        // 填充0（Arrays.copyOf已自动填充0，无需额外操作）
//
//        // 2. 按大端序解析每4字节为无符号整数并累加
//        long sum = 0;
//        for (int i = 0; i < paddedLength; i += 4) {
//            int value = ((paddedData[i] & 0xFF) << 24) |
//                    ((paddedData[i + 1] & 0xFF) << 16) |
//                    ((paddedData[i + 2] & 0xFF) << 8) |
//                    (paddedData[i + 3] & 0xFF);
//            sum += value & 0xFFFFFFFFL; // 作为无符号数处理
//        }
//
//        // 3. 取模得到32位结果
//        return (int) (sum & 0xFFFFFFFFL);
//    }

    /**
     * 解压单个表数据（支持多种压缩算法）
     */
    private byte[] decompress(byte[] data, int compLen, int origLen, int compressionType) throws IOException {
        if (origLen == compLen) { // More precise check
            return data;
        }
        try {
            byte[] decompressed = switch (compressionType) {
                case COMPRESSION_ZLIB -> zlibDecompress(data);
                case COMPRESSION_BROTLI -> brotliDecompress(data);
                default ->
                        throw new IOException("Unsupported compression format: " + compressionType);
            };
            // 校验解压后长度
            if (decompressed.length != origLen) {
                throw new IOException("Decompressed length mismatch: expected=" + origLen + ", actual=" + decompressed.length);
            }
            return decompressed;
        } catch (Exception e) {
            throw new IOException("Decompression failed for type " + compressionType, e);
        }
    }

    /**
     * 使用 ZLIB 解压（替代 Zopfli）
     */
    private byte[] zlibDecompress(byte[] data) throws IOException {
        try (InputStream is = new ByteArrayInputStream(data);
             InflaterInputStream inflater = new InflaterInputStream(is)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = inflater.read(buffer)) != -1) {
                bos.write(buffer, 0, n);
            }
            return bos.toByteArray();
        }
    }

    /**
     * 使用 Brotli 解压（需要 Android API 19+）
     */
    private byte[] brotliDecompress(byte[] data) throws IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            throw new IOException("Brotli requires Android 4.4+");
        }
        try (BrotliInputStream brotli = new BrotliInputStream(new ByteArrayInputStream(data))) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = brotli.read(buffer)) != -1) {
                bos.write(buffer, 0, n);
            }
            return bos.toByteArray();
        }
    }

    /**
     * 判断是否为元数据或私有数据
     */
    private boolean isMetadataOrPrivate(WOFFTableDirectory table, WOFFHeader header) {
        return table.offset == header.metaOffset || table.offset == header.privOffset;
    }

    /**
     * 整数转字节数组（大端）
     */
    private byte[] intToBytes(int value) {
        return new byte[]{
                (byte) (value >> 24),
                (byte) (value >> 16),
                (byte) (value >> 8),
                (byte) value
        };
    }

    /**
     * 短整型转字节数组（大端）
     */
    private byte[] shortToBytes(short value) {
        return new byte[]{
                (byte) (value >> 8),
                (byte) value
        };
    }

    /**
     * 读取 WOFF 文件头
     */
    private WOFFHeader readWOFFHeader(BufferReader reader) throws IOException {
        WOFFHeader header = new WOFFHeader();

        // 1. 读取签名（4字节ASCII）
        header.signature = reader.ReadString4();
        if (!"wOFF".equals(header.signature) && !"wOF2".equals(header.signature)) {
            throw new IOException("Invalid WOFF signature: " + header.signature);
        }

        // 2. 读取其他字段（大端序）
        header.flavor = reader.ReadUInt32();
        header.length = reader.ReadUInt32();
        header.numTables = reader.ReadUInt16();
        header.reserved = reader.ReadUInt16();
        header.totalSfntSize = reader.ReadUInt32();
        header.majorVersion = reader.ReadUInt16();
        header.minorVersion = reader.ReadUInt16();
        header.metaOffset = reader.ReadUInt32();  // 强制转换（可能溢出需检查）
        header.metaLength = reader.ReadUInt32();
        header.metaOrigLength = reader.ReadUInt32();
        header.privOffset = reader.ReadUInt32();
        header.privLength = reader.ReadUInt32();

        return header;
    }

    /**
     * 读取 WOFF 表目录
     */
    private WOFFTableDirectory[] readWOFFTables(BufferReader reader, WOFFHeader header) {
        WOFFTableDirectory[] tables = new WOFFTableDirectory[header.numTables];
        reader.position(44);  // 正确跳过44字节文件头
        for (int i = 0; i < header.numTables; i++) {
            WOFFTableDirectory table = new WOFFTableDirectory();
            table.tag = reader.ReadString4(); // 读取4字节ASCII
            table.offset = reader.ReadUInt32();
            table.compLength = reader.ReadUInt32();
            table.origLength = reader.ReadUInt32();
            table.origChecksum = reader.ReadUInt32();
            tables[i] = table;
        }
        return tables;
    }

    // 修改参数列表，移除 Map，直接记录表信息
    private void writeTableData(
            ByteArrayOutputStream outputStream,
            WOFFTableDirectory table,
            byte[] data,
            List<TableInfo> tableInfos  // 新增：记录表信息的对象列表
    ) throws IOException {
        int currentPos = outputStream.size();
        outputStream.write(data);

        // 添加4字节对齐填充
        int padding = (4 - (data.length % 4)) % 4;
        if (padding > 0) {
            outputStream.write(new byte[padding]);
        }

        // 记录表的 offset、length、checksum
        TableInfo info = new TableInfo();
        info.tag = table.tag;
        info.checksum = calculateChecksum(data);
        info.offset = currentPos;  // 数据区的绝对偏移
        info.length = data.length;
        tableInfos.add(info);
    }

    // 辅助类：存储表信息
    private static class TableInfo {
        String tag;
        int checksum;
        int offset;
        int length;
    }
}
