package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("upload")
public class UploadController {
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final Pattern OWNED = Pattern.compile("^/blogs/users/([1-9][0-9]*)/([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})\\.(jpg|jpeg|png|gif|webp)$");
    private final Path root;

    public UploadController(@Value("${hmdp.upload.dir:}") String uploadDir) {
        this.root = Paths.get(uploadDir.isEmpty() ? SystemConstants.IMAGE_UPLOAD_DIR : uploadDir).toAbsolutePath().normalize();
    }

    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) throws IOException {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null || user.getId() <= 0) return Result.fail("请先登录");
        String original = image.getOriginalFilename();
        int dot = original == null ? -1 : original.lastIndexOf('.');
        String ext = dot < 0 ? "" : original.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ext.matches("jpg|jpeg|png|gif|webp") || image.isEmpty() || image.getSize() > MAX_BYTES)
            return Result.fail("仅支持不超过5MB的图片");
        byte[] bytes;
        try (InputStream in = image.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int n, total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_BYTES) return Result.fail("仅支持不超过5MB的图片");
                out.write(buf, 0, n);
            }
            bytes = out.toByteArray();
        }
        if (!matchesImage(ext, bytes)) return Result.fail("图片格式无效");
        Path dir = userDirectory(user.getId(), true);
        String name = UUID.randomUUID().toString() + "." + ext;
        Files.write(dir.resolve(name), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return Result.ok("/blogs/users/" + user.getId() + "/" + name);
    }

    @GetMapping("/blog/delete")
    public Result deleteBlogImg(@RequestParam("name") String filename) throws IOException {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) return Result.fail("请先登录");
        String name = filename.startsWith("/imgs/") ? filename.substring(5) : filename;
        Matcher match = OWNED.matcher(name);
        if (!match.matches() || !Long.toString(user.getId()).equals(match.group(1))) return Result.fail("无权删除该图片");
        Path dir = userDirectory(user.getId(), false);
        if (dir == null) return Result.ok();
        Path file = dir.resolve(match.group(2) + "." + match.group(3));
        if (Files.isSymbolicLink(file) || (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)))
            return Result.fail("错误的文件名称");
        Files.deleteIfExists(file);
        return Result.ok();
    }

    private Path userDirectory(long userId, boolean create) throws IOException {
        if (!create && !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return null;
        if (create) Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) throw new IOException("上传目录不能是符号链接");
        Path realRoot = root.toRealPath();
        Path dir = realRoot;
        for (String segment : new String[]{"blogs", "users", Long.toString(userId)}) {
            dir = dir.resolve(segment);
            if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
                if (!create) return null;
                try { Files.createDirectory(dir); } catch (java.nio.file.FileAlreadyExistsException ignored) { }
            }
            if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) throw new IOException("无效的上传目录");
        }
        Path realDir = dir.toRealPath();
        if (!realDir.startsWith(realRoot)) throw new IOException("无效的上传目录");
        return realDir;
    }

    private static boolean matchesImage(String ext, byte[] b) {
        if ((ext.equals("jpg") || ext.equals("jpeg")) && b.length >= 3) return (b[0]&255)==255 && (b[1]&255)==216 && (b[2]&255)==255;
        if (ext.equals("png") && b.length >= 8) return (b[0]&255)==137 && b[1]==80 && b[2]==78 && b[3]==71 && b[4]==13 && b[5]==10 && b[6]==26 && b[7]==10;
        if (ext.equals("gif") && b.length >= 6) return b[0]=='G' && b[1]=='I' && b[2]=='F' && b[3]=='8' && (b[4]=='7'||b[4]=='9') && b[5]=='a';
        return ext.equals("webp") && b.length >= 12 && b[0]=='R' && b[1]=='I' && b[2]=='F' && b[3]=='F' && b[8]=='W' && b[9]=='E' && b[10]=='B' && b[11]=='P';
    }
}
