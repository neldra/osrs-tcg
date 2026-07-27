package com.osrstcg.ui;

import com.osrstcg.data.CardDefinition;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import org.junit.Assert;
import org.junit.Test;

/**
 * Pins the art-placeholder states: a card whose artwork terminally failed to load reads
 * "Artwork unavailable" instead of an eternal "Loading artwork..." (upstream issue #79 —
 * fetch failures used to render identically to loading), while cards with art, cards
 * still loading, and cards without an image URL are unchanged.
 */
public class SharedCardRendererArtStateTest
{
	@Test
	public void loadingCardKeepsLoadingText()
	{
		Assert.assertEquals("Loading artwork...", SharedCardRenderer.artPlaceholderText(cardWithUrl(), false));
	}

	@Test
	public void failedCardReadsArtworkUnavailable()
	{
		Assert.assertEquals("Artwork unavailable", SharedCardRenderer.artPlaceholderText(cardWithUrl(), true));
	}

	@Test
	public void cardWithoutImageUrlReadsNoArtworkRegardlessOfFailedFlag()
	{
		CardDefinition card = new CardDefinition();
		card.setName("Test Card");
		Assert.assertEquals("No artwork", SharedCardRenderer.artPlaceholderText(card, false));
		Assert.assertEquals("No artwork", SharedCardRenderer.artPlaceholderText(card, true));
	}

	@Test
	public void failedStateChangesTheRenderedFace()
	{
		BufferedImage loading = renderFace(null, false);
		BufferedImage failed = renderFace(null, true);

		Assert.assertFalse("failed art must render differently from loading art",
			Arrays.equals(pixels(loading), pixels(failed)));
	}

	@Test
	public void failedFlagIsIgnoredWhenArtIsPresent()
	{
		BufferedImage art = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g2 = art.createGraphics();
		g2.setColor(Color.ORANGE);
		g2.fillRect(0, 0, 64, 64);
		g2.dispose();

		BufferedImage withArt = renderFace(art, false);
		BufferedImage withArtFailedFlag = renderFace(art, true);

		Assert.assertArrayEquals("present art must win over the failed flag",
			pixels(withArt), pixels(withArtFailedFlag));
	}

	private static BufferedImage renderFace(BufferedImage linkedImage, boolean artFailed)
	{
		BufferedImage canvas = new BufferedImage(200, 280, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g2 = canvas.createGraphics();
		SharedCardRenderer.drawCardFace(g2, new Rectangle(0, 0, 200, 280), cardWithUrl(), false,
			Color.GRAY, linkedImage, 0L, false, true, artFailed);
		g2.dispose();
		return canvas;
	}

	private static CardDefinition cardWithUrl()
	{
		CardDefinition card = new CardDefinition();
		card.setName("Test Card");
		card.setImageUrl("https://oldschool.runescape.wiki/images/thumb/Test_detail.png/130px-Test_detail.png");
		card.setExamine("A test card.");
		return card;
	}

	private static int[] pixels(BufferedImage image)
	{
		return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
	}
}
