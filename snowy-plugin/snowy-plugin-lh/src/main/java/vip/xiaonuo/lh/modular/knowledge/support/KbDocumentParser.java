package vip.xiaonuo.lh.modular.knowledge.support;

import cn.hutool.core.util.StrUtil;
import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.SAXException;
import vip.xiaonuo.common.exception.CommonException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Set;

/**
 * 知识库文档正文抽取（Apache Tika）。
 * <p>白名单：pdf / docx / doc / md / txt / html / htm；失败抛业务异常，不返回占位假文本。</p>
 */
public final class KbDocumentParser {

    /** 与前端 KB_UPLOAD_EXT 对齐 */
    public static final Set<String> SUPPORTED_EXT = Set.of(
            "pdf", "docx", "doc", "md", "txt", "html", "htm");

    /** 单文件上限 20MB（与 doc/知识库.md 建议一致；容器 multipart 上限更大） */
    public static final long MAX_BYTES = 20L * 1024 * 1024;

    /** 防止超大文档撑爆内存；超过则截断并仍可入库 */
    private static final int WRITE_LIMIT = 8 * 1024 * 1024;

    private KbDocumentParser() {
    }

    public record ParseResult(String text, String fileName, String ext, int charCount) {
    }

    public static ParseResult parse(String originalFilename, long size, InputStream in) {
        if (in == null) {
            throw new CommonException("上传文件为空");
        }
        if (size > MAX_BYTES) {
            throw new CommonException("文件过大，单文件上限 {} MB", MAX_BYTES / (1024 * 1024));
        }
        String fileName = StrUtil.blankToDefault(originalFilename, "upload.bin").trim();
        String ext = extensionOf(fileName);
        if (ext == null || !SUPPORTED_EXT.contains(ext)) {
            throw new CommonException("不支持的文件类型：{}；仅支持 pdf / docx / doc / md / txt / html",
                    StrUtil.blankToDefault(ext, "未知"));
        }

        BodyContentHandler handler = new BodyContentHandler(WRITE_LIMIT);
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
        AutoDetectParser parser = new AutoDetectParser();
        ParseContext context = new ParseContext();
        boolean truncated = false;
        try {
            parser.parse(in, handler, metadata, context);
        } catch (EncryptedDocumentException e) {
            throw new CommonException("文档已加密，无法解析：{}", fileName);
        } catch (WriteLimitReachedException e) {
            truncated = true;
        } catch (SAXException e) {
            if (WriteLimitReachedException.isWriteLimitReached(e)) {
                truncated = true;
            } else {
                throw new CommonException("文档损坏或无法解析：{}（{}）",
                        fileName, StrUtil.maxLength(StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()), 160));
            }
        } catch (TikaException e) {
            throw new CommonException("文档损坏或无法解析：{}（{}）",
                    fileName, StrUtil.maxLength(StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()), 160));
        } catch (IOException e) {
            throw new CommonException("读取上传文件失败：{}",
                    StrUtil.maxLength(StrUtil.blankToDefault(e.getMessage(), "IO"), 120));
        }

        String raw = handler.toString();
        String text = normalizeExtracted(raw);
        if (StrUtil.isBlank(text)) {
            throw new CommonException("未能从文档抽取到有效正文（可能是扫描件 PDF 或空文件）：{}", fileName);
        }
        if (truncated) {
            text = text + "\n\n[正文已截断：超出单文档抽取上限]";
        }
        return new ParseResult(text, fileName, ext, text.length());
    }

    static String extensionOf(String fileName) {
        if (StrUtil.isBlank(fileName) || !fileName.contains(".")) {
            return null;
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).trim().toLowerCase(Locale.ROOT);
    }

    /** 归一空白，保留段落结构 */
    static String normalizeExtracted(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replace("\r\n", "\n").replace('\r', '\n');
        // 压缩连续空行，去掉首尾空白
        s = s.replaceAll("[ \\t\\x0B\\f]+", " ");
        s = s.replaceAll(" *\\n *", "\n");
        s = s.replaceAll("\\n{3,}", "\n\n");
        return s.trim();
    }
}
