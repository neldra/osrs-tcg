package com.osrstcg.ui.collectionalbum;

import com.osrstcg.data.CardDefinition;
import com.osrstcg.service.TestWikiImageCaches;
import com.osrstcg.service.WikiImageCacheService;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Pins progressive repaint behavior: when a card's art arrives, exactly that face is
 * re-rendered in place — other faces stay on screen untouched, and the page is never
 * blanked back to flat placeholder rectangles.
 */
public class CollectionAlbumGridProgressiveTest
{
	// Unroutable host: a regression that reaches the network fails fast inside a unit test.
	private static final String URL_A = "https://127.0.0.1:1/images/Alpha_detail.png";
	private static final String URL_B = "https://127.0.0.1:1/images/Beta_detail.png";
	/** Solid art color chosen to be unmistakable in painted output. */
	private static final Color ART_MAGENTA = new Color(0xFF00FF);

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void artArrivalRefreshesOnlyThatFaceAndBlanksNothing() throws Exception
	{
		Path dir = tmp.getRoot().toPath();
		WikiImageCacheService images = TestWikiImageCaches.withCacheDir(dir);
		CollectionAlbumGridPanel[] panel = new CollectionAlbumGridPanel[1];
		SwingUtilities.invokeAndWait(() ->
		{
			panel[0] = new CollectionAlbumGridPanel(images, (i, s) ->
			{
			}, s ->
			{
			}, () ->
			{
			}, s ->
			{
			});
			panel[0].setSize(600, 400);
			panel[0].setSlots(List.of(slot("Alpha card", URL_A), slot("Beta card", URL_B)));
		});

		// Initial rasters render with no art in memory: card frames + "Loading artwork...".
		Assert.assertTrue("initial face rasters should render", awaitCondition(
			() -> hasRenderedFace(paint(panel[0]))));
		Assert.assertFalse("no art should be on screen yet", hasArt(paint(panel[0])));

		// URL_A's art lands on disk and is loaded into memory (as a settle would leave it).
		writeArtPng(dir, URL_A);
		images.preload(List.of(URL_A));
		Assert.assertTrue("art should reach the memory cache", awaitCondition(
			() -> images.isInMemory(URL_A)));

		// The per-slot refresh must paint the new art without blanking the other face.
		SwingUtilities.invokeAndWait(() ->
			panel[0].refreshFaceForUrl(images.normalizeImageUrl(URL_A)));
		Assert.assertTrue("other faces must stay rendered immediately after the refresh",
			hasRenderedFace(paint(panel[0])));
		Assert.assertTrue("the arrived art should appear", awaitCondition(
			() -> hasArt(paint(panel[0]))));
	}

	private static AlbumSlot slot(String name, String imageUrl)
	{
		CardDefinition card = new CardDefinition();
		card.setName(name);
		card.setImageUrl(imageUrl);
		return new AlbumSlot(card, new Color(0xC0A030), true, false, 1, 0, null, false, null, false);
	}

	private static void writeArtPng(Path dir, String url) throws Exception
	{
		BufferedImage image = new BufferedImage(120, 120, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setColor(ART_MAGENTA);
		g.fillRect(0, 0, 120, 120);
		g.dispose();
		ImageIO.write(image, "png", dir.resolve(sha256Hex(url) + ".png").toFile());
	}

	private interface Condition
	{
		boolean check() throws Exception;
	}

	private static boolean awaitCondition(Condition condition) throws Exception
	{
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < deadline)
		{
			if (condition.check())
			{
				return true;
			}
			Thread.sleep(25);
		}
		return false;
	}

	private static BufferedImage paint(CollectionAlbumGridPanel panel) throws Exception
	{
		BufferedImage img = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
		SwingUtilities.invokeAndWait(() ->
		{
			Graphics2D g = img.createGraphics();
			panel.paint(g);
			g.dispose();
		});
		return img;
	}

	/** True when any pixel is strongly saturated non-magenta — a rasterized card frame. */
	private static boolean hasRenderedFace(BufferedImage img)
	{
		return scan(img, rgb -> saturation(rgb) > 30 && !isMagentaish(rgb));
	}

	/** True when the magenta art fill is on screen. */
	private static boolean hasArt(BufferedImage img)
	{
		return scan(img, CollectionAlbumGridProgressiveTest::isMagentaish);
	}

	private static boolean isMagentaish(int rgb)
	{
		int r = (rgb >> 16) & 0xFF;
		int g = (rgb >> 8) & 0xFF;
		int b = rgb & 0xFF;
		return r > 180 && b > 180 && g < 80;
	}

	private static int saturation(int rgb)
	{
		int r = (rgb >> 16) & 0xFF;
		int g = (rgb >> 8) & 0xFF;
		int b = rgb & 0xFF;
		return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
	}

	private interface PixelPredicate
	{
		boolean test(int rgb);
	}

	private static boolean scan(BufferedImage img, PixelPredicate predicate)
	{
		for (int y = 10; y < img.getHeight() - 10; y += 4)
		{
			for (int x = 10; x < img.getWidth() - 10; x += 4)
			{
				if (predicate.test(img.getRGB(x, y) & 0xFFFFFF))
				{
					return true;
				}
			}
		}
		return false;
	}

	private static String sha256Hex(String value) throws Exception
	{
		byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		StringBuilder sb = new StringBuilder(digest.length * 2);
		for (byte b : digest)
		{
			sb.append("0123456789abcdef".charAt((b >> 4) & 0xF)).append("0123456789abcdef".charAt(b & 0xF));
		}
		return sb.toString();
	}
}
