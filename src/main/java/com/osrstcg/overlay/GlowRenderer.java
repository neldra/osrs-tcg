package com.osrstcg.overlay;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Draws the soft card-edge glow used by the pack reveal. Every face-up card glows at one
 * constant alpha, so its glow can be pre-rendered once per (color, size) and blitted each
 * frame instead of re-filling the layered translucent rects on the game render thread.
 * Src-over compositing is associative, so the blit matches the direct draw except for
 * 8-bit rounding in the intermediate image (measured within 5/255 per channel).
 */
class GlowRenderer
{
	private static final float GLOW_MAX_EXPAND = 26f;
	private static final int GLOW_LAYERS = 18;
	private static final int GLOW_BASE_ARC = 20;
	private static final int GLOW_PAD = (int) Math.ceil(GLOW_MAX_EXPAND);
	/** ARGB byte estimate; card glows are ~1.4 MB at max zoom, so this holds ~11 of them. */
	private static final long CACHE_BUDGET_BYTES = 16L * 1024 * 1024;

	/** Touched only from {@code Overlay.render} on the client render thread; unsynchronized. */
	private final Map<GlowKey, BufferedImage> cache = new LinkedHashMap<>(32, 0.75f, true);
	private long cacheBytes;

	void drawCachedGlow(Graphics2D g, Rectangle r, Color color, float alpha)
	{
		if (Math.max(0f, Math.min(1f, alpha)) <= 0.01f)
		{
			return;
		}
		g.drawImage(glowImageFor(color, r.width, r.height, alpha), r.x - GLOW_PAD, r.y - GLOW_PAD, null);
	}

	BufferedImage glowImageFor(Color color, int width, int height, float alpha)
	{
		Color glow = color == null ? Color.WHITE : color;
		float clamped = Math.max(0f, Math.min(1f, alpha));
		GlowKey key = new GlowKey(glow.getRGB(), width, height, Float.floatToIntBits(clamped));
		BufferedImage cached = cache.get(key);
		if (cached != null)
		{
			return cached;
		}
		// Premultiplied so the per-layer composites don't round-trip through unpremultiplied 8-bit.
		BufferedImage img = new BufferedImage(
			width + GLOW_PAD * 2, height + GLOW_PAD * 2, BufferedImage.TYPE_INT_ARGB_PRE);
		Graphics2D g2 = img.createGraphics();
		try
		{
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			paintGlow(g2, new Rectangle(GLOW_PAD, GLOW_PAD, width, height), glow, clamped);
		}
		finally
		{
			g2.dispose();
		}
		cache.put(key, img);
		cacheBytes += estimateBytes(img);
		// Access-order put leaves the new entry at the tail; eviction from the head never
		// removes it, and size() > 1 keeps the sole remaining entry even over budget.
		var eldestIt = cache.values().iterator();
		while (cacheBytes > CACHE_BUDGET_BYTES && cache.size() > 1 && eldestIt.hasNext())
		{
			cacheBytes -= estimateBytes(eldestIt.next());
			eldestIt.remove();
		}
		return img;
	}

	long cacheBytes()
	{
		return cacheBytes;
	}

	/**
	 * Stable soft edge glow from card bounds — the layered direct draw, used for animated
	 * alphas and to render the cached image.
	 */
	static void paintGlow(Graphics2D g, Rectangle r, Color color, float alpha)
	{
		Color glow = color == null ? Color.WHITE : color;
		float clampedAlpha = Math.max(0f, Math.min(1f, alpha));
		if (clampedAlpha <= 0.01f)
		{
			return;
		}

		Graphics2D g2 = (Graphics2D) g.create();
		try
		{
			for (int i = GLOW_LAYERS; i >= 1; i--)
			{
				float t = (float) i / (float) GLOW_LAYERS; // 1 near card, 0 far.
				int expand = Math.max(1, Math.round((1.0f - t) * GLOW_MAX_EXPAND));
				float falloff = t * t; // smooth quadratic falloff
				float layerAlpha = clampedAlpha * falloff * 0.34f;
				g2.setColor(withAlpha(glow, layerAlpha));
				int arc = GLOW_BASE_ARC + expand;
				g2.fillRoundRect(
					r.x - expand,
					r.y - expand,
					r.width + (expand * 2),
					r.height + (expand * 2),
					arc,
					arc
				);
			}
		}
		finally
		{
			g2.dispose();
		}
	}

	private static long estimateBytes(BufferedImage img)
	{
		return (long) img.getWidth() * img.getHeight() * 4;
	}

	private static Color withAlpha(Color color, float alpha)
	{
		int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
		return new Color(color.getRed(), color.getGreen(), color.getBlue(), a);
	}

	private static final class GlowKey
	{
		private final int rgb;
		private final int width;
		private final int height;
		private final int alphaBits;

		GlowKey(int rgb, int width, int height, int alphaBits)
		{
			this.rgb = rgb;
			this.width = width;
			this.height = height;
			this.alphaBits = alphaBits;
		}

		@Override
		public boolean equals(Object o)
		{
			if (!(o instanceof GlowKey))
			{
				return false;
			}
			GlowKey other = (GlowKey) o;
			return rgb == other.rgb && width == other.width
				&& height == other.height && alphaBits == other.alphaBits;
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(rgb, width, height, alphaBits);
		}
	}
}
