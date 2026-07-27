package com.osrstcg.ui.collectionalbum;

import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/** Pins the pages chosen for background image prefetch after a page-turn. */
public class CollectionAlbumPrefetchTest
{
	@Test
	public void prefetchesNextTwoPagesWhenBrowsingForward()
	{
		Assert.assertEquals(List.of(3, 4), CollectionAlbumWindow.prefetchPages(2, 10, 1));
	}

	@Test
	public void prefetchesPreviousTwoPagesWhenBrowsingBackward()
	{
		Assert.assertEquals(List.of(1, 0), CollectionAlbumWindow.prefetchPages(2, 10, -1));
	}

	@Test
	public void clampsDepthNearTheEndOfTheAlbum()
	{
		Assert.assertEquals(List.of(9), CollectionAlbumWindow.prefetchPages(8, 10, 1));
	}

	@Test
	public void fallsBackToPreviousPageOnLastPage()
	{
		Assert.assertEquals(List.of(8), CollectionAlbumWindow.prefetchPages(9, 10, 1));
	}

	@Test
	public void fallsBackToNextPageOnFirstPage()
	{
		Assert.assertEquals(List.of(1), CollectionAlbumWindow.prefetchPages(0, 10, -1));
	}

	@Test
	public void returnsNothingWhenNoOtherPageExists()
	{
		Assert.assertEquals(List.of(), CollectionAlbumWindow.prefetchPages(0, 1, 1));
		Assert.assertEquals(List.of(), CollectionAlbumWindow.prefetchPages(0, 0, -1));
	}
}
