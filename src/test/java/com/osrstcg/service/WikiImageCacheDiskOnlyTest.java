package com.osrstcg.service;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Pins the disk-only fly-by contract: {@link WikiImageCacheService#loadDiskOnly} serves
 * disk-cached art without admitting it to the memory cache and without touching the
 * network, and reports misses as a null callback instead of fetching.
 */
public class WikiImageCacheDiskOnlyTest
{
	// Unroutable host: a regression that reaches the network fails fast inside a unit test.
	private static final String URL_A = "https://127.0.0.1:1/images/Alpha_detail.png";
	private static final String URL_MISSING = "https://127.0.0.1:1/images/Missing_detail.png";

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void diskHitDeliversImageWithoutMemoryAdmission() throws Exception
	{
		Path dir = tmp.getRoot().toPath();
		writeArtPng(dir, URL_A);
		WikiImageCacheService images = TestWikiImageCaches.withCacheDir(dir);

		BufferedImage image = awaitDiskOnly(images, URL_A);

		Assert.assertNotNull("disk-cached art should be delivered", image);
		Assert.assertFalse("fly-by loads must not admit to the memory cache", images.isInMemory(URL_A));
	}

	@Test
	public void diskMissReportsNullWithoutFetching() throws Exception
	{
		WikiImageCacheService images = TestWikiImageCaches.withCacheDir(tmp.getRoot().toPath());

		BufferedImage image = awaitDiskOnly(images, URL_MISSING);

		Assert.assertNull("a miss must report null, not fetch", image);
		Assert.assertFalse(images.isInMemory(URL_MISSING));
	}

	@Test
	public void memoryHitDeliversWithoutDiskRead() throws Exception
	{
		Path dir = tmp.getRoot().toPath();
		writeArtPng(dir, URL_A);
		WikiImageCacheService images = TestWikiImageCaches.withCacheDir(dir);
		images.preload(List.of(URL_A));
		Assert.assertTrue("preload should settle into memory", awaitCondition(() -> images.isInMemory(URL_A)));

		BufferedImage image = awaitDiskOnly(images, URL_A);

		Assert.assertNotNull("memory-cached art should be delivered", image);
	}

	@Test
	public void staleJobsAreSkippedWithoutDecodingOrCallingBack() throws Exception
	{
		Path dir = tmp.getRoot().toPath();
		writeArtPng(dir, URL_A);
		writeArtPng(dir, URL_MISSING);
		WikiImageCacheService images = TestWikiImageCaches.withCacheDir(dir);

		// The lane is FIFO: by the time the live job's callback fires, the stale job
		// queued ahead of it has been processed — and must have been dropped.
		AtomicBoolean staleDelivered = new AtomicBoolean();
		images.loadDiskOnly(URL_MISSING, () -> false, image -> staleDelivered.set(true));
		BufferedImage live = awaitDiskOnly(images, URL_A);

		Assert.assertNotNull("live job should still deliver", live);
		Assert.assertFalse("stale job must be dropped without a callback", staleDelivered.get());
	}

	private static BufferedImage awaitDiskOnly(WikiImageCacheService images, String url) throws Exception
	{
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<BufferedImage> result = new AtomicReference<>();
		images.loadDiskOnly(url, () -> true, image ->
		{
			result.set(image);
			done.countDown();
		});
		Assert.assertTrue("fly-by callback should fire", done.await(10, TimeUnit.SECONDS));
		return result.get();
	}

	private static void writeArtPng(Path dir, String url) throws Exception
	{
		BufferedImage image = new BufferedImage(120, 120, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setColor(new Color(0xFF00FF));
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
