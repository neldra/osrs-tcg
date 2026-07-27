package com.osrstcg.service;

/**
 * Folds terminal wiki-image load failures into a few high-signal log lines: the first
 * failure of a session reports immediately with full detail, later ones aggregate into
 * a summary at most once per window. A fully blocked client (e.g. a Cloudflare
 * challenge the plugin cannot pass) fails every card on every retry — a warn per
 * failure would flood the log without adding evidence.
 */
final class WikiImageFailureLog
{
	private static final long SUMMARY_WINDOW_MS = 5 * 60_000L;

	private boolean firstReported;
	private long windowStartMs;
	private int foldedFailures;

	/**
	 * Records one terminal failure; returns the log line to emit, or null while the
	 * current window is still folding failures.
	 */
	synchronized String record(String url, String cause, long nowMs)
	{
		if (!firstReported)
		{
			firstReported = true;
			windowStartMs = nowMs;
			return "Wiki image load failed: " + url + " (" + cause
				+ "); artwork will show as unavailable until a retry succeeds";
		}
		foldedFailures++;
		if (nowMs - windowStartMs < SUMMARY_WINDOW_MS)
		{
			return null;
		}
		String line = foldedFailures + (foldedFailures == 1 ? " wiki image load" : " wiki image loads")
			+ " failed since the last report (latest: " + cause + ")";
		foldedFailures = 0;
		windowStartMs = nowMs;
		return line;
	}
}
