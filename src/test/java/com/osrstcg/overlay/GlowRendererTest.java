package com.osrstcg.overlay;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import org.junit.Assert;
import org.junit.Test;

/**
 * Characterizes the cached reveal glow against drawing the layered glow directly. Exact
 * equality is impossible for any pre-composited cache: the direct path rounds to 8-bit
 * once per layer (18 times) onto the live background, while the blit rounds once into
 * the cache and once out. Measured worst case is 5/255 at the reveal's production alpha;
 * the test bounds it at 8/255 (~3%) per channel for cross-platform headroom —
 * imperceptible for a glow whose individual layers are under 6% alpha.
 */
public class GlowRendererTest
{
	/** The reveal's face-up glow alpha (the only alpha the cache serves in production). */
	private static final float PRODUCTION_ALPHA = 0.17f;

	@Test
	public void cachedGlowMatchesDirectDrawWithinRounding()
	{
		GlowRenderer renderer = new GlowRenderer();
		Color[] colors = {new Color(0xF2C94C), new Color(0xFF6EC7), new Color(0x9B59B6)};
		float[] alphas = {PRODUCTION_ALPHA, 0.85f};
		Rectangle rect = new Rectangle(60, 60, 120, 170);

		for (float alpha : alphas)
		{
			for (Color color : colors)
			{
				BufferedImage direct = paintedScene(g ->
					GlowRenderer.paintGlow(g, rect, color, alpha));
				BufferedImage cached = paintedScene(g ->
					renderer.drawCachedGlow(g, rect, color, alpha));
				int maxDiff = maxChannelDiff(direct, cached);
				Assert.assertTrue("cached glow differs from direct draw by " + maxDiff
					+ "/255 (bound: 8, see class javadoc) at alpha " + alpha + " for " + color,
					maxDiff <= 8);
			}
		}
	}

	@Test
	public void sameColorAndSizeReusesTheCachedImage()
	{
		GlowRenderer renderer = new GlowRenderer();
		Assert.assertSame(
			renderer.glowImageFor(Color.RED, 90, 130, PRODUCTION_ALPHA),
			renderer.glowImageFor(Color.RED, 90, 130, PRODUCTION_ALPHA));
	}

	@Test
	public void cacheStaysWithinByteBudget()
	{
		GlowRenderer renderer = new GlowRenderer();
		for (int i = 0; i < 100; i++)
		{
			// Mix of card-sized and large (max-zoom) glows.
			renderer.glowImageFor(Color.RED, 200 + i * 7, 300 + i * 9, PRODUCTION_ALPHA);
		}
		Assert.assertTrue("cache bytes " + renderer.cacheBytes() + " exceed the budget",
			renderer.cacheBytes() <= 16L * 1024 * 1024);
		Assert.assertTrue("newest entry must survive eviction", renderer.cacheBytes() > 0);
	}

	@Test
	public void nearZeroAlphaDrawsNothingAndCachesNothing()
	{
		GlowRenderer renderer = new GlowRenderer();
		Rectangle rect = new Rectangle(60, 60, 120, 170);
		BufferedImage untouched = paintedScene(g ->
		{
		});
		BufferedImage zeroDirect = paintedScene(g ->
			GlowRenderer.paintGlow(g, rect, Color.RED, 0f));
		BufferedImage zeroCached = paintedScene(g ->
			renderer.drawCachedGlow(g, rect, Color.RED, 0f));
		Assert.assertEquals(0, maxChannelDiff(untouched, zeroDirect));
		Assert.assertEquals(0, maxChannelDiff(untouched, zeroCached));
		Assert.assertEquals("zero-alpha draws must not mint cache entries", 0, renderer.cacheBytes());
	}

	private interface ScenePainter
	{
		void paint(Graphics2D g);
	}

	/** Paints onto a fixed gradient background (glow compositing depends on what's under it). */
	private static BufferedImage paintedScene(ScenePainter painter)
	{
		BufferedImage img = new BufferedImage(260, 300, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setPaint(new GradientPaint(0, 0, new Color(0x123047), 260, 300, new Color(0x3B1F1F)));
		g.fillRect(0, 0, 260, 300);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		painter.paint(g);
		g.dispose();
		return img;
	}

	private static int maxChannelDiff(BufferedImage a, BufferedImage b)
	{
		int max = 0;
		for (int y = 0; y < a.getHeight(); y++)
		{
			for (int x = 0; x < a.getWidth(); x++)
			{
				int pa = a.getRGB(x, y);
				int pb = b.getRGB(x, y);
				for (int shift = 0; shift <= 16; shift += 8)
				{
					max = Math.max(max, Math.abs(((pa >> shift) & 0xFF) - ((pb >> shift) & 0xFF)));
				}
			}
		}
		return max;
	}
}
