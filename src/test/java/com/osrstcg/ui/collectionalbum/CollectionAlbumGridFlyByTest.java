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
 * Pins the fly-by tier: applying a page whose art is on disk (but not in memory) paints
 * that art without any preload call, without admitting it to the memory cache, and
 * without reaching the network — so scroll-past pages show art instead of placeholders.
 */
public class CollectionAlbumGridFlyByTest
{
	// Unroutable host: a regression that reaches the network fails fast inside a unit test.
	private static final String URL_A = "https://127.0.0.1:1/images/Alpha_detail.png";
	private static final String URL_B = "https://127.0.0.1:1/images/Beta_detail.png";
	private static final Color ART_MAGENTA = new Color(0xFF00FF);
	private static final Color ART_CYAN = new Color(0x00FFFF);

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void diskWarmPageAppliesArtWithoutPreloadOrMemoryAdmission() throws Exception
	{
		Path dir = tmp.getRoot().toPath();
		writeArtPng(dir, URL_A, ART_MAGENTA);
		WikiImageCacheService images = TestWikiImageCaches.withCacheDir(dir);
		CollectionAlbumGridPanel panel = newPanel(images);

		SwingUtilities.invokeAndWait(() ->
			panel.setSlots(List.of(slot("Alpha card", URL_A))));

		Assert.assertTrue("disk-warm art should paint without any preload", awaitCondition(
			() -> hasColor(paint(panel), CollectionAlbumGridFlyByTest::isMagentaish)));
		Assert.assertFalse("fly-by art must not be admitted to the memory cache",
			images.isInMemory(URL_A));
	}

	@Test
	public void artFromALeftPageNeverPaintsOverTheCurrentPage() throws Exception
	{
		Path dir = tmp.getRoot().toPath();
		writeArtPng(dir, URL_A, ART_MAGENTA);
		writeArtPng(dir, URL_B, ART_CYAN);
		WikiImageCacheService images = TestWikiImageCaches.withCacheDir(dir);
		CollectionAlbumGridPanel panel = newPanel(images);

		// Flip to a second page before the first page's fly-by decode can land.
		SwingUtilities.invokeAndWait(() ->
		{
			panel.setSlots(List.of(slot("Alpha card", URL_A)));
			panel.setSlots(List.of(slot("Beta card", URL_B)));
		});

		Assert.assertTrue("landed page's art should paint", awaitCondition(
			() -> hasColor(paint(panel), CollectionAlbumGridFlyByTest::isCyanish)));
		Assert.assertFalse("left page's art must not appear on the current page",
			hasColor(paint(panel), CollectionAlbumGridFlyByTest::isMagentaish));
	}

	@Test
	public void memoryWarmArtPaintsTheSameFrameThePageApplies() throws Exception
	{
		Path dir = tmp.getRoot().toPath();
		writeArtPng(dir, URL_A, ART_MAGENTA);
		WikiImageCacheService images = TestWikiImageCaches.withCacheDir(dir);
		images.preload(List.of(URL_A));
		Assert.assertTrue("preload should settle into memory", awaitCondition(
			() -> images.isInMemory(URL_A)));
		CollectionAlbumGridPanel panel = newPanel(images);

		// Paint inside the same EDT event as the page apply: async rasters cannot have
		// landed yet, so any art on screen must come from the direct-draw fallback.
		BufferedImage first = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
		SwingUtilities.invokeAndWait(() ->
		{
			panel.setSlots(List.of(slot("Alpha card", URL_A)));
			Graphics2D g = first.createGraphics();
			panel.paint(g);
			g.dispose();
		});

		Assert.assertTrue("memory-warm art must draw on the very first paint",
			hasColor(first, CollectionAlbumGridFlyByTest::isMagentaish));
	}

	@Test
	public void samePageRefreshKeepsFlyByArt() throws Exception
	{
		Path dir = tmp.getRoot().toPath();
		writeArtPng(dir, URL_A, ART_MAGENTA);
		WikiImageCacheService images = TestWikiImageCaches.withCacheDir(dir);
		CollectionAlbumGridPanel panel = newPanel(images);
		SwingUtilities.invokeAndWait(() ->
			panel.setSlots(List.of(slot("Alpha card", URL_A))));
		Assert.assertTrue("fly-by art should paint", awaitCondition(
			() -> hasColor(paint(panel), CollectionAlbumGridFlyByTest::isMagentaish)));

		// A collection change re-applies the same page. With the disk copy gone, the art
		// can only survive if the fly-by map retains entries still on the page.
		java.nio.file.Files.delete(dir.resolve(sha256Hex(URL_A) + ".png"));
		SwingUtilities.invokeAndWait(() ->
			panel.setSlots(List.of(slot("Alpha card", URL_A))));

		Assert.assertTrue("art must survive a same-page refresh", awaitCondition(
			() -> hasColor(paint(panel), CollectionAlbumGridFlyByTest::isMagentaish)));
	}

	@Test
	public void resizeKeepsFacesOnScreenViaTheOldRasters() throws Exception
	{
		Path dir = tmp.getRoot().toPath();
		writeArtPng(dir, URL_A, ART_MAGENTA);
		writeArtPng(dir, URL_B, ART_CYAN);
		// One-image budget: preloading URL_B evicts URL_A from memory.
		WikiImageCacheService images = TestWikiImageCaches.withCacheDirAndBudget(dir, 1L);
		images.preload(List.of(URL_A));
		Assert.assertTrue("art should settle into memory", awaitCondition(
			() -> images.isInMemory(URL_A)));
		CollectionAlbumGridPanel panel = newPanel(images);
		SwingUtilities.invokeAndWait(() ->
			panel.setSlots(List.of(slot("Alpha card", URL_A))));
		Assert.assertTrue("rasters should complete at the first size", awaitCondition(
			() -> hasColor(paint(panel), CollectionAlbumGridFlyByTest::isMagentaish)));

		images.preload(List.of(URL_B));
		Assert.assertTrue("second image should evict the first", awaitCondition(
			() -> !images.isInMemory(URL_A)));

		// First paint at the new size, same EDT event: re-rasters cannot have landed, and
		// the art is in neither the memory cache nor the fly-by map — only the old raster
		// can keep the face on screen.
		BufferedImage first = new BufferedImage(500, 340, BufferedImage.TYPE_INT_RGB);
		SwingUtilities.invokeAndWait(() ->
		{
			panel.setSize(500, 340);
			Graphics2D g = first.createGraphics();
			panel.paint(g);
			g.dispose();
		});

		Assert.assertTrue("faces must stay on screen across a resize",
			hasColor(first, CollectionAlbumGridFlyByTest::isMagentaish));
	}

	private static CollectionAlbumGridPanel newPanel(WikiImageCacheService images) throws Exception
	{
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
		});
		return panel[0];
	}

	private static AlbumSlot slot(String name, String imageUrl)
	{
		CardDefinition card = new CardDefinition();
		card.setName(name);
		card.setImageUrl(imageUrl);
		return new AlbumSlot(card, new Color(0xC0A030), true, false, 1, 0, null, false, null, false);
	}

	private static void writeArtPng(Path dir, String url, Color color) throws Exception
	{
		BufferedImage image = new BufferedImage(120, 120, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setColor(color);
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

	private static boolean isMagentaish(int rgb)
	{
		int r = (rgb >> 16) & 0xFF;
		int g = (rgb >> 8) & 0xFF;
		int b = rgb & 0xFF;
		return r > 180 && b > 180 && g < 80;
	}

	private static boolean isCyanish(int rgb)
	{
		int r = (rgb >> 16) & 0xFF;
		int g = (rgb >> 8) & 0xFF;
		int b = rgb & 0xFF;
		return g > 180 && b > 180 && r < 80;
	}

	private interface PixelPredicate
	{
		boolean test(int rgb);
	}

	private static boolean hasColor(BufferedImage img, PixelPredicate predicate)
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
