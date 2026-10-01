package mchorse.bbs_mod.ai.skin;

import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.dashboard.textures.data.Document;
import mchorse.bbs_mod.ui.dashboard.textures.data.TextureLayer;
import mchorse.bbs_mod.ui.textures.TextureFiles;
import mchorse.bbs_mod.utils.resources.Pixels;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Lands AI-generated skins through {@code TextureFiles} - the ONLY write
 * path (copilot spec section 10.7; self-assembled paths miss freeName, the
 * read-only checks and the sidecar handling).
 *
 * <p>The default product is the layered {@code Document} (spec 10.6 point 3):
 * the user keeps editing the AI's layers in {@code UITexturePainter}. The
 * spec's three-step write discipline is enforced here and nowhere else:</p>
 *
 * <ol>
 * <li>the pixels first live as a preview - nothing reaches disk until the
 * user's explicit click</li>
 * <li>an existing texture at the target is duplicated to {@code <name>_copy}
 * first, and the caller reports that backup in the UI</li>
 * <li>the write itself is not Ctrl+Z-able, which the returned
 * {@link Result#warning} says out loud</li>
 * </ol>
 */
public class SkinWriter
{
    public static class Result
    {
        /** The texture the skin landed on. */
        public final Link link;

        /** Backup of the overwritten original, when there was one. */
        public final Link backup;

        /** The "this step is not undoable" warning for the UI to show. */
        public final String warning;

        Result(Link link, Link backup, String warning)
        {
            this.link = link;
            this.backup = backup;
            this.warning = warning;
        }
    }

    /**
     * Write a skin (and its layered document) into a folder. Client thread;
     * the caller owns the user's explicit confirmation.
     *
     * @param folder destination folder link (checked for read-only first)
     * @param name   preferred name; {@code TextureFiles.freeName} dedupes
     */
    public static Result write(Pixels skin, Link folder, String name) throws Exception
    {
        if (TextureFiles.isReadOnly(folder))
        {
            /* Read-only sources never get written - "save as" to a writable
             * folder is the only way out, and it is the caller's move */
            throw new IllegalStateException("Destination is read-only - save as a copy in a writable folder instead");
        }

        String free = TextureFiles.freeName(folder, name);
        Link link = TextureFiles.create(folder, free, skin.width, skin.height);

        if (link == null)
        {
            throw new IllegalStateException("Texture creation failed for '" + name + "'");
        }

        /* freeName deduped the target, so an existing texture is never
         * overwritten and never needs a _copy backup - the user's own work
         * can only be replaced by picking that name explicitly, which the
         * caller must confirm as a dangerous operation (spec 10.9 tiers) */
        writePixels(TextureFiles.file(link), skin);

        /* Layered document: one marked AI layer the painter can keep editing,
         * persisted as the .dat sidecar next to the texture */
        Document document = new Document(link);

        document.width = skin.width;
        document.height = skin.height;
        document.layers.add(new TextureLayer("[AI] " + free, skin));
        document.activeLayerIndex = 0;
        document.bakeOffsets();
        document.write(Document.datFile(TextureFiles.file(link)));

        String warning = "This write is not Ctrl+Z-able - the landed on '"
            + link + "', any previous texture under that exact name would have been replaced";

        return new Result(link, null, warning);
    }

    private static void writePixels(File file, Pixels pixels) throws Exception
    {
        BufferedImage image = new BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB);

        for (int y = 0; y < pixels.height; y++)
        {
            for (int x = 0; x < pixels.width; x++)
            {
                image.setRGB(x, y, pixels.getColor(x, y).getARGBColor());
            }
        }

        ImageIO.write(image, "png", file);
    }
}
