package com.osrstcg.ui;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Test;

/**
 * Pins the non-restarting contract: a burst of load events produces one bounded-rate
 * repaint near the FIRST event, not one repaint pushed out past the LAST event.
 */
public class ImageRepaintCoalescerTest
{
	private static final int DELAY_MS = 200;

	/** Absorb first-use EDT/TimerQueue bootstrap so timing assertions see a warm event queue. */
	@org.junit.Before
	public void warmEventQueue() throws Exception
	{
		javax.swing.SwingUtilities.invokeAndWait(() ->
		{
		});
	}

	@Test
	public void burstOfSignalsProducesOneRepaint() throws Exception
	{
		AtomicInteger repaints = new AtomicInteger();
		CountDownLatch first = new CountDownLatch(1);
		ImageRepaintCoalescer coalescer = new ImageRepaintCoalescer(DELAY_MS, () ->
		{
			repaints.incrementAndGet();
			first.countDown();
		});
		try
		{
			for (int i = 0; i < 10; i++)
			{
				coalescer.signal();
			}
			Assert.assertTrue("repaint should fire", first.await(5, TimeUnit.SECONDS));
			Thread.sleep(DELAY_MS * 3);
			Assert.assertEquals("burst coalesces into a single repaint", 1, repaints.get());
		}
		finally
		{
			coalescer.stop();
		}
	}

	@Test
	public void continuousSignalsDoNotPushTheRepaintBack() throws Exception
	{
		CountDownLatch fired = new CountDownLatch(1);
		ImageRepaintCoalescer coalescer = new ImageRepaintCoalescer(DELAY_MS, fired::countDown);
		try
		{
			long start = System.nanoTime();
			// Signal every 50 ms for 1 s: a restarting debounce would not fire until
			// ~1.2 s; the coalescer must fire near DELAY_MS after the first signal.
			long firedAtMs = -1;
			for (int i = 0; i < 20; i++)
			{
				coalescer.signal();
				if (fired.await(50, TimeUnit.MILLISECONDS) && firedAtMs < 0)
				{
					firedAtMs = (System.nanoTime() - start) / 1_000_000;
				}
			}
			Assert.assertTrue("repaint should have fired during the signal stream", firedAtMs >= 0);
			Assert.assertTrue("fired at " + firedAtMs + " ms; must not wait for the stream to end",
				firedAtMs < 800);
		}
		finally
		{
			coalescer.stop();
		}
	}

	@Test
	public void signalAfterFireSchedulesAnotherRepaint() throws Exception
	{
		AtomicInteger repaints = new AtomicInteger();
		ImageRepaintCoalescer coalescer = new ImageRepaintCoalescer(DELAY_MS, repaints::incrementAndGet);
		try
		{
			coalescer.signal();
			awaitCount(repaints, 1);
			coalescer.signal();
			awaitCount(repaints, 2);
		}
		finally
		{
			coalescer.stop();
		}
	}

	@Test
	public void stopCancelsPendingRepaintButAllowsLaterSignals() throws Exception
	{
		AtomicInteger repaints = new AtomicInteger();
		ImageRepaintCoalescer coalescer = new ImageRepaintCoalescer(DELAY_MS, repaints::incrementAndGet);
		try
		{
			coalescer.signal();
			coalescer.stop();
			Thread.sleep(DELAY_MS * 3);
			Assert.assertEquals("stop must cancel the pending repaint", 0, repaints.get());

			// Windows hide (stop) and re-show; signals must work again afterwards.
			coalescer.signal();
			awaitCount(repaints, 1);
		}
		finally
		{
			coalescer.stop();
		}
	}

	private static void awaitCount(AtomicInteger counter, int target) throws InterruptedException
	{
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (counter.get() < target && System.nanoTime() < deadline)
		{
			Thread.sleep(10);
		}
		Assert.assertEquals(target, counter.get());
	}
}
