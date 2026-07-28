package com.osrstcg.service;

import java.util.concurrent.TimeUnit;
import org.junit.Assert;
import org.junit.Test;

/**
 * Pins the failure-log policy: the first terminal image-load failure of a session is
 * reported immediately with full detail, later failures are folded into an aggregate
 * summary emitted at most once per window — so a fully blocked client produces a few
 * high-signal warn lines instead of one per card per retry.
 */
public class WikiImageFailureLogTest
{
	private static final String URL = "https://oldschool.runescape.wiki/images/thumb/Alpha_detail.png/130px-Alpha_detail.png";
	private static final long START_MS = 1_000_000L;

	private final WikiImageFailureLog failureLog = new WikiImageFailureLog();

	@Test
	public void firstFailureIsReportedImmediatelyWithUrlAndCause()
	{
		String line = failureLog.record(URL, "HTTP 403 (cf-ray abc123)", START_MS);

		Assert.assertNotNull("first failure must be reported", line);
		Assert.assertTrue(line.contains(URL));
		Assert.assertTrue(line.contains("HTTP 403 (cf-ray abc123)"));
	}

	@Test
	public void failuresInsideTheWindowAreFoldedSilently()
	{
		failureLog.record(URL, "HTTP 403", START_MS);

		Assert.assertNull(failureLog.record(URL, "HTTP 403", START_MS + 1_000L));
		Assert.assertNull(failureLog.record(URL, "HTTP 403", START_MS + 2_000L));
	}

	@Test
	public void aggregateSummaryReportsCountAndLatestCauseAfterWindow()
	{
		failureLog.record(URL, "HTTP 403", START_MS);
		failureLog.record(URL, "HTTP 403", START_MS + 1_000L);
		failureLog.record(URL, "SocketTimeoutException", START_MS + 2_000L);

		String summary = failureLog.record(URL, "UnknownHostException", START_MS + TimeUnit.MINUTES.toMillis(6));

		Assert.assertNotNull("elapsed window must produce a summary", summary);
		Assert.assertTrue("summary should count folded failures: " + summary, summary.contains("3"));
		Assert.assertTrue("summary should carry the latest cause: " + summary, summary.contains("UnknownHostException"));
	}

	@Test
	public void summaryEmissionRestartsTheWindow()
	{
		failureLog.record(URL, "HTTP 403", START_MS);
		failureLog.record(URL, "HTTP 403", START_MS + TimeUnit.MINUTES.toMillis(6));

		Assert.assertNull("window restarts after each emission",
			failureLog.record(URL, "HTTP 403", START_MS + TimeUnit.MINUTES.toMillis(7)));
	}
}
