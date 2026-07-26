package com.osrstcg.service;

import java.nio.file.Path;
import okhttp3.OkHttpClient;

/** Builds {@link WikiImageCacheService} instances on a temp cache dir for tests in other packages. */
public final class TestWikiImageCaches
{
	private TestWikiImageCaches()
	{
	}

	public static WikiImageCacheService withCacheDir(Path dir)
	{
		return new WikiImageCacheService(new OkHttpClient(), dir, 32L * 1024 * 1024);
	}

	public static WikiImageCacheService withCacheDirAndBudget(Path dir, long memoryBudgetBytes)
	{
		return new WikiImageCacheService(new OkHttpClient(), dir, memoryBudgetBytes);
	}
}
