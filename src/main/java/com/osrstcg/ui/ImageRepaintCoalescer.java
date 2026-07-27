package com.osrstcg.ui;

import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Coalesces bursts of image-load events into bounded-rate repaints. Unlike a restarting
 * debounce, signals arriving while a repaint is already pending do not push it back, so
 * the first image of a burst is painted within {@code delayMs} even while later loads
 * are still streaming in.
 */
public final class ImageRepaintCoalescer
{
	private final Timer timer;

	public ImageRepaintCoalescer(int delayMs, Runnable repaint)
	{
		timer = new Timer(delayMs, e -> repaint.run());
		timer.setRepeats(false);
	}

	/** Schedules a repaint unless one is already pending. Safe to call from any thread. */
	public void signal()
	{
		SwingUtilities.invokeLater(() ->
		{
			if (!timer.isRunning())
			{
				timer.start();
			}
		});
	}

	/** Cancels any pending repaint; later signals schedule normally (windows hide and re-show). */
	public void stop()
	{
		SwingUtilities.invokeLater(timer::stop);
	}
}
