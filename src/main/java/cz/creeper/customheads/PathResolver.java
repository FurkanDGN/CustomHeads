package cz.creeper.customheads;

import com.google.common.collect.Maps;
import com.sun.org.apache.xerces.internal.impl.dv.util.HexBin;
import lombok.Getter;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.format.TextColors;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Getter
public class PathResolver {
    public static final String DIRECTORY_NAME_CACHE = "cache";
    private final CustomHeads plugin;
    private final Executor asyncExecutor;
    private final Map<String, CompletableFuture<Path>> pathMap = Maps.newHashMap();

    public PathResolver(CustomHeads plugin) {
        this.plugin = plugin;
        asyncExecutor = Sponge.getScheduler().createAsyncExecutor(plugin);
    }

    public CompletableFuture<Path> resolvePath(String path) {
        CompletableFuture<Path> result = pathMap.get(path);

        if(result == null || result.isDone()
                && (result.isCancelled() || result.isCompletedExceptionally())) {
            result = computePath(path);

            pathMap.put(path, result);
        }

        return result;
    }

    private CompletableFuture<Path> computePath(String rawPath) {
        final URL url;

        try {
            url = new URL(rawPath);
        } catch (MalformedURLException e) {
            Path path = Paths.get(rawPath);

            if(Files.isRegularFile(path))
                return CompletableFuture.completedFuture(path);
            else {
                CompletableFuture<Path> result = new CompletableFuture<>();

                result.completeExceptionally(
                        new TextException(
                                Text.of(TextColors.RED, "Could not find the specified file: '" + rawPath + "'."),
                                null
                        )
                );

                return result;
            }
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                String pathMd5 = calculateMd5(url.toExternalForm());
                String outputFileName = pathMd5 + ".png";
                Path outputPath = plugin.getConfigDir()
                        .resolve(DIRECTORY_NAME_CACHE)
                        .resolve(outputFileName);

                if(Files.isRegularFile(outputPath))
                    return outputPath;

                Files.createDirectories(outputPath.getParent());

                plugin.getLogger().info("Downloading player skin '" + rawPath + "' to '" + outputPath + "'.");

                ReadableByteChannel rbc = Channels.newChannel(url.openStream());
                FileOutputStream fos = new FileOutputStream(outputPath.toFile());

                // Limit the number of bytes by 2^16 (65536), skins are small.
                long bytes = 1 << 12;
                long transferredBytes = fos.getChannel().transferFrom(rbc, 0, bytes);

                if(transferredBytes >= bytes) {
                    Files.deleteIfExists(outputPath);
                    throw new TextException(Text.of(TextColors.RED, "The file at the specified URL was too large"
                            + " to download, skins usually aren't as large."), null);
                }

                return outputPath;
            } catch (IOException e) {
                e.printStackTrace();
                throw new TextException(Text.of(TextColors.RED, "Could not download the skin from the specified URL."), e);
            }
        }, asyncExecutor);
    }

    private static String calculateMd5(String data) {
        try {
            byte[] bytesOfMessage = data.getBytes("UTF-8");

            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(bytesOfMessage);

            return HexBin.encode(digest);
        } catch(UnsupportedEncodingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
