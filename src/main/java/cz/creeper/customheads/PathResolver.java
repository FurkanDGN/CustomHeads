package cz.creeper.customheads;

import com.google.common.collect.Maps;
import com.sun.org.apache.xerces.internal.impl.dv.util.HexBin;
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

/**
 * Manages texture downloading and caching
 */
public class PathResolver {
    public static final String DIRECTORY_NAME_CACHE = "cache";
    private final CustomHeads plugin;
    private final Executor asyncExecutor;
    private final Map<String, CompletableFuture<Path>> pathMap = Maps.newHashMap();

    public PathResolver(CustomHeads plugin) {
        this.plugin = plugin;
        asyncExecutor = Sponge.getScheduler().createAsyncExecutor(plugin);
    }

    /**
     * @param path The path to resolve
     * @return An existing future that is being executed, or a new one
     */
    public CompletableFuture<Path> resolvePath(String path) {
        CompletableFuture<Path> result = pathMap.get(path);

        if(result == null || result.isDone()
                && (result.isCancelled() || result.isCompletedExceptionally())) {
            result = computePath(path);

            pathMap.put(path, result);
        }

        return result;
    }

    /**
     * If a URL is provided, checks whether a texture of the exact same URL has been downloaded already and returns it;
     * if not, downloads it and returns the path to the downloaded file.
     *
     * If a file path is provided, returns the path to the file, if it's an actual regular file.
     *
     * @param rawPath The path to resolve
     * @return A future promising a Path to a local file
     */
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
                throw new TextException(Text.of(TextColors.RED, "Could not download the skin from the specified URL."), e);
            }
        }, asyncExecutor);
    }

    /**
     * Calculates the MD5 hash of a specified {@link String}.
     *
     * @param data The {@link String} to process
     * @return The MD5 hash of the provided {@link String}
     */
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
