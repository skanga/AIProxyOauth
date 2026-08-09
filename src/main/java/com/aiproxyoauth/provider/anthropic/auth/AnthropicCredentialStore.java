package com.aiproxyoauth.provider.anthropic.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

public final class AnthropicCredentialStore
        implements AnthropicCredentialRepository, AutoCloseable {
    private static final int MAX_CREDENTIAL_BYTES = 64 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
    );

    private final Path path;
    private final FileChannel lockChannel;
    private final FileLock fileLock;
    private final ReentrantLock operationLock = new ReentrantLock();
    private boolean closed;

    private AnthropicCredentialStore(Path path, FileChannel lockChannel, FileLock fileLock) {
        this.path = path;
        this.lockChannel = lockChannel;
        this.fileLock = fileLock;
    }

    public static AnthropicCredentialStore open(Path requestedPath) throws IOException {
        Objects.requireNonNull(requestedPath, "requestedPath");
        Path path = requestedPath.toAbsolutePath().normalize();
        Path parent = path.getParent();
        if (parent == null) {
            throw new IOException("Anthropic credential file must have a parent directory");
        }
        Files.createDirectories(parent);
        Path lockPath = parent.resolve(path.getFileName() + ".lock");
        FileChannel channel = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
        );
        applyOwnerOnlyPermissions(lockPath);
        try {
            FileLock lease;
            try {
                lease = channel.tryLock();
            } catch (OverlappingFileLockException error) {
                lease = null;
            }
            if (lease == null) {
                channel.close();
                throw new IOException("Anthropic credential file is already in use: " + path);
            }
            return new AnthropicCredentialStore(path, channel, lease);
        } catch (IOException | RuntimeException error) {
            if (channel.isOpen()) {
                channel.close();
            }
            throw error;
        }
    }

    public Path path() {
        return path;
    }

    public Optional<AnthropicCredential> load() throws IOException {
        operationLock.lock();
        try {
            ensureOpen();
            if (!Files.exists(path)) {
                return Optional.empty();
            }
            JsonNode root;
            try (var input = Files.newInputStream(path)) {
                byte[] bytes = input.readNBytes(MAX_CREDENTIAL_BYTES + 1);
                if (bytes.length > MAX_CREDENTIAL_BYTES) {
                    throw new IOException("Anthropic credential file is too large");
                }
                root = JSON.readTree(bytes);
            } catch (RuntimeException error) {
                throw new IOException("Invalid Anthropic credential file", error);
            }
            if (root == null || root.path("version").asInt(-1) != 1
                    || !"anthropic".equals(root.path("provider").asText())) {
                throw new IOException("Unsupported Anthropic credential file");
            }
            try {
                return Optional.of(new AnthropicCredential(
                        requiredText(root, "access_token"),
                        requiredText(root, "refresh_token"),
                        Instant.parse(requiredText(root, "expires_at")),
                        Instant.parse(requiredText(root, "updated_at"))
                ));
            } catch (IllegalArgumentException | DateTimeParseException error) {
                throw new IOException("Invalid Anthropic credential file", error);
            }
        } finally {
            operationLock.unlock();
        }
    }

    public void save(AnthropicCredential credential) throws IOException {
        Objects.requireNonNull(credential, "credential");
        operationLock.lock();
        Path temporary = null;
        try {
            ensureOpen();
            Path parent = path.getParent();
            temporary = Files.createTempFile(parent, path.getFileName() + ".", ".tmp");
            applyOwnerOnlyPermissions(temporary);

            ObjectNode root = JSON.createObjectNode();
            root.put("version", 1);
            root.put("provider", "anthropic");
            root.put("access_token", credential.accessToken());
            root.put("refresh_token", credential.refreshToken());
            root.put("expires_at", credential.expiresAt().toString());
            root.put("updated_at", credential.updatedAt().toString());
            byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            )) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
            applyOwnerOnlyPermissions(path);
        } finally {
            if (temporary != null) {
                Files.deleteIfExists(temporary);
            }
            operationLock.unlock();
        }
    }

    public void clear() throws IOException {
        operationLock.lock();
        try {
            ensureOpen();
            Files.deleteIfExists(path);
        } finally {
            operationLock.unlock();
        }
    }

    @Override
    public void close() throws IOException {
        operationLock.lock();
        try {
            if (!closed) {
                closed = true;
                try {
                    fileLock.release();
                } finally {
                    lockChannel.close();
                }
            }
        } finally {
            operationLock.unlock();
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Anthropic credential store is closed");
        }
    }

    private static String requiredText(JsonNode root, String field) throws IOException {
        String value = root.path(field).asText();
        if (value.isBlank()) {
            throw new IOException("Anthropic credential field is missing: " + field);
        }
        return value;
    }

    private static void applyOwnerOnlyPermissions(Path target) throws IOException {
        if (Files.getFileStore(target).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(target, OWNER_ONLY);
        }
    }
}
