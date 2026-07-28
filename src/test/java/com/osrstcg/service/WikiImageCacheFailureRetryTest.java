package com.osrstcg.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Pins failed-URL handling: a URL whose load terminally fails is reported via
 * {@link WikiImageCacheService#isFailed}, is not re-fetched while its retry cooldown
 * runs, and is retried by the lazy {@link WikiImageCacheService#getCached} path once
 * the cooldown expires — recovering fully when the network does.
 */
public class WikiImageCacheFailureRetryTest
{
	private static final String URL = "https://oldschool.runescape.wiki/images/thumb/Alpha_detail.png/130px-Alpha_detail.png";

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private final AtomicLong nowMs = new AtomicLong(1_000_000L);
	private final AtomicInteger requests = new AtomicInteger();
	private final AtomicBoolean serveArt = new AtomicBoolean(false);

	@Test
	public void failedLoadMarksUrlFailedAndSkipsRefetchWithinCooldown() throws Exception
	{
		WikiImageCacheService images = newService();

		Assert.assertNull(awaitSettled(images, URL));

		Assert.assertTrue("terminal failure should mark the URL failed", images.isFailed(URL));
		int requestsAfterFailure = requests.get();

		nowMs.addAndGet(1_000L);
		Assert.assertNull(images.getCached(URL));
		// getCached must not have queued a load; give a stray async job time to surface.
		Thread.sleep(150L);
		Assert.assertEquals("within cooldown no re-fetch may start", requestsAfterFailure, requests.get());
	}

	@Test
	public void retryAfterCooldownRecoversAndClearsFailedState() throws Exception
	{
		WikiImageCacheService images = newService();

		Assert.assertNull(awaitSettled(images, URL));
		Assert.assertTrue(images.isFailed(URL));

		serveArt.set(true);
		nowMs.addAndGet(TimeUnit.MINUTES.toMillis(2));

		Assert.assertNotNull("expired cooldown should allow the lazy path to retry", awaitSettled(images, URL));
		Assert.assertFalse("successful retry should clear the failed state", images.isFailed(URL));
	}

	@Test
	public void failedUrlStaysSettledAndNeedsNoLoadEvenAfterCooldown() throws Exception
	{
		WikiImageCacheService images = newService();

		Assert.assertNull(awaitSettled(images, URL));

		Assert.assertTrue("failed URL must count as settled", images.isSettled(URL));
		Assert.assertFalse("failed URL must not demand a load", images.needsLoad(URL));

		nowMs.addAndGet(TimeUnit.MINUTES.toMillis(2));
		Assert.assertTrue("cooldown expiry must not unsettle preload paths", images.isSettled(URL));
		Assert.assertFalse(images.needsLoad(URL));
		Assert.assertTrue("failed state should read as failed until a retry succeeds", images.isFailed(URL));
	}

	@Test
	public void preloadWithinCooldownDoesNotRefetch() throws Exception
	{
		WikiImageCacheService images = newService();

		Assert.assertNull(awaitSettled(images, URL));
		int requestsAfterFailure = requests.get();

		// Page flips preload every visible URL; a fully blocked client must not re-issue
		// them on each flip while the cooldown runs.
		nowMs.addAndGet(1_000L);
		images.preload(List.of(URL));
		Thread.sleep(150L);
		Assert.assertEquals("preload must respect the failure cooldown", requestsAfterFailure, requests.get());
	}

	@Test
	public void repeatedFailureRestartsTheCooldown() throws Exception
	{
		WikiImageCacheService images = newService();

		Assert.assertNull(awaitSettled(images, URL));
		nowMs.addAndGet(TimeUnit.MINUTES.toMillis(2));

		Assert.assertNull("retry against a still-broken network fails again", awaitSettled(images, URL));
		int requestsAfterSecondFailure = requests.get();

		nowMs.addAndGet(1_000L);
		Assert.assertNull(images.getCached(URL));
		Thread.sleep(150L);
		Assert.assertEquals("second failure must restart the cooldown", requestsAfterSecondFailure, requests.get());
	}

	@Test
	public void terminalFailureWarnsOnceWithHttpStatusAndCfRay() throws Exception
	{
		Logger serviceLogger = (Logger) org.slf4j.LoggerFactory.getLogger(WikiImageCacheService.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		serviceLogger.addAppender(appender);
		try
		{
			WikiImageCacheService images = newService();
			Assert.assertNull(awaitSettled(images, URL));

			List<String> warns = appender.list.stream()
				.filter(event -> event.getLevel() == Level.WARN)
				.map(ILoggingEvent::getFormattedMessage)
				.collect(Collectors.toList());
			Assert.assertEquals("one warn per first failure: " + warns, 1, warns.size());
			Assert.assertTrue("warn should carry the HTTP status: " + warns.get(0), warns.get(0).contains("HTTP 403"));
			Assert.assertTrue("warn should carry the cf-ray id: " + warns.get(0), warns.get(0).contains("test-ray-1234"));
		}
		finally
		{
			serviceLogger.detachAppender(appender);
		}
	}

	private WikiImageCacheService newService()
	{
		OkHttpClient client = new OkHttpClient.Builder()
			.addInterceptor(chain ->
			{
				requests.incrementAndGet();
				if (serveArt.get())
				{
					return new Response.Builder()
						.request(chain.request())
						.protocol(Protocol.HTTP_1_1)
						.code(200)
						.message("OK")
						.body(ResponseBody.create(MediaType.get("image/png"), pngBytes()))
						.build();
				}
				return new Response.Builder()
					.request(chain.request())
					.protocol(Protocol.HTTP_1_1)
					.code(403)
					.message("Forbidden")
					.header("cf-ray", "test-ray-1234")
					.body(ResponseBody.create(MediaType.get("text/html"), "blocked"))
					.build();
			})
			.build();
		return new WikiImageCacheService(client, tmp.getRoot().toPath(), 32L * 1024 * 1024, nowMs::get);
	}

	/** Kicks the lazy load via getCached and waits for the URL to settle (cached or failed). */
	private BufferedImage awaitSettled(WikiImageCacheService images, String url) throws Exception
	{
		CountDownLatch settled = new CountDownLatch(1);
		images.addLoadListener(u -> settled.countDown());
		BufferedImage immediate = images.getCached(url);
		if (immediate != null)
		{
			return immediate;
		}
		Assert.assertTrue("image load should settle", settled.await(5, TimeUnit.SECONDS));
		return images.getIfPresent(url);
	}

	private static byte[] pngBytes()
	{
		BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g2 = image.createGraphics();
		g2.setColor(Color.ORANGE);
		g2.fillRect(0, 0, 64, 64);
		g2.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try
		{
			ImageIO.write(image, "png", out);
		}
		catch (IOException ex)
		{
			throw new IllegalStateException(ex);
		}
		return out.toByteArray();
	}
}
