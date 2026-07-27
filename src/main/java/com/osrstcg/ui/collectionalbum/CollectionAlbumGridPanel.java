package com.osrstcg.ui.collectionalbum;

import com.osrstcg.data.CardDefinition;
import com.osrstcg.service.WikiImageCacheService;
import com.osrstcg.ui.SharedCardRenderer;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.FontManager;

final class CollectionAlbumGridPanel extends JPanel
{
	private static final int COLS = 7;
	private static final int ROWS = 3;
	private static final int GAP = 5;
	/** Vertical space below each card face for the owned-count line (Runescape small + padding). */
	private static final int QTY_LABEL_RESERVE_PX = 18;
	private static final Color SELECTION_BORDER = new Color(0x00E5FF);
	private static final Color OFFERED_TRADE_BORDER = new Color(0x3DDC84);
	private static final Color PLACEHOLDER_FACE = new Color(0x2A2A2A);

	private final WikiImageCacheService imageCacheService;
	private final BiConsumer<Integer, AlbumSlot> ownedMultiCopyPressed;
	private final Consumer<AlbumSlot> onLockToggle;
	private final Runnable onSelectionChanged;
	private final Consumer<AlbumSlot> onDoubleClickOffer;
	private List<AlbumSlot> slots = Collections.emptyList();
	private List<Rectangle> lastCardBounds = Collections.emptyList();
	private int selectedIndex = -1;

	/**
	 * Art delivered by fly-by disk loads, held only for the current slot list (cleared on
	 * the next {@link #setSlots}) — never admitted to the service's memory cache, so
	 * scroll sprees cannot churn the LRU. Read by the raster lane, written on the EDT.
	 */
	private final Map<String, BufferedImage> transientArt = new ConcurrentHashMap<>();
	/** Bumped on every setSlots; fly-by callbacks from a left page check it and drop. */
	private final AtomicLong slotsGen = new AtomicLong();

	/** Off-EDT rasterized card faces (no animated foil overlays); painted via blit only. */
	private BufferedImage[] faceRasters = new BufferedImage[0];
	/**
	 * The same slots' rasters at the previous size, scale-blitted while a resize
	 * re-rasters — cheaper than re-drawing faces on the EDT during a drag-resize.
	 */
	private BufferedImage[] prevFaceRasters = new BufferedImage[0];
	private int faceRasterW;
	private int faceRasterH;
	private final AtomicLong faceRasterGen = new AtomicLong();
	/** Generation currently being rasterized; avoids re-queueing every paint while faces load. */
	private long scheduledFaceGen = -1L;
	private final AtomicBoolean faceRepaintScheduled = new AtomicBoolean();
	/**
	 * Own lane for face rasterization (pure CPU, ~1ms/face). On the shared wiki-image pool
	 * these jobs would queue behind pending network decodes, leaving the grid blank for the
	 * duration of a cold page load. Idle thread times out, so no shutdown hook is needed.
	 */
	private final ExecutorService faceRasterExecutor = new ThreadPoolExecutor(
		0, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r ->
		{
			Thread t = new Thread(r, "osrs-tcg-album-face-raster");
			t.setDaemon(true);
			return t;
		});

	CollectionAlbumGridPanel(WikiImageCacheService imageCacheService,
		BiConsumer<Integer, AlbumSlot> ownedMultiCopyPressed,
		Consumer<AlbumSlot> onLockToggle,
		Runnable onSelectionChanged,
		Consumer<AlbumSlot> onDoubleClickOffer)
	{
		this.imageCacheService = imageCacheService;
		this.ownedMultiCopyPressed = ownedMultiCopyPressed;
		this.onLockToggle = onLockToggle;
		this.onSelectionChanged = onSelectionChanged == null ? () -> {} : onSelectionChanged;
		this.onDoubleClickOffer = onDoubleClickOffer;
		setOpaque(true);
		setBackground(new Color(0x1E1E1E));
		setMinimumSize(new Dimension(400, 260));
		setPreferredSize(new Dimension(720, 420));
		setToolTipText("");

		addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (tryLockToggle(e))
				{
					return;
				}
				if (e != null && e.getClickCount() >= 2)
				{
					return;
				}
				handlePress(e);
			}

			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (e == null || !SwingUtilities.isLeftMouseButton(e) || e.getClickCount() != 2)
				{
					return;
				}
				handleDoubleClick(e);
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				tryLockToggle(e);
			}
		});
	}

	void clearSelection()
	{
		selectedIndex = -1;
		repaint();
	}

	int getSelectedIndex()
	{
		return selectedIndex;
	}

	AlbumSlot getSelectedSlot()
	{
		if (selectedIndex < 0 || selectedIndex >= slots.size())
		{
			return null;
		}
		return slots.get(selectedIndex);
	}

	private boolean tryLockToggle(MouseEvent e)
	{
		if (e == null || !e.isPopupTrigger() || onLockToggle == null)
		{
			return false;
		}
		int idx = hitTestSlotIndex(e);
		if (idx < 0 || idx >= slots.size())
		{
			return false;
		}
		AlbumSlot slot = slots.get(idx);
		if (slot == null || !slot.ownedAny() || slot.soleInstanceId() == null)
		{
			return false;
		}
		onLockToggle.accept(slot);
		return true;
	}

	private int hitTestSlotIndex(MouseEvent e)
	{
		for (int i = 0; i < lastCardBounds.size(); i++)
		{
			Rectangle r = lastCardBounds.get(i);
			if (r != null && r.contains(e.getPoint()))
			{
				return i;
			}
		}
		return -1;
	}

	private void handlePress(MouseEvent e)
	{
		if (e == null || SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger())
		{
			return;
		}
		int next = -1;
		for (int i = 0; i < lastCardBounds.size(); i++)
		{
			Rectangle r = lastCardBounds.get(i);
			if (r != null && r.contains(e.getPoint()))
			{
				next = i;
				break;
			}
		}
		if (next < 0 || next >= slots.size() || !slots.get(next).ownedAny())
		{
			return;
		}
		AlbumSlot slot = slots.get(next);
		if (slot.totalOwnedQty() > 1)
		{
			selectedIndex = -1;
			repaint();
			onSelectionChanged.run();
			if (ownedMultiCopyPressed != null)
			{
				ownedMultiCopyPressed.accept(next, slot);
			}
			return;
		}
		if (next == selectedIndex)
		{
			selectedIndex = -1;
		}
		else
		{
			selectedIndex = next;
		}
		repaint();
		onSelectionChanged.run();
	}

	private void handleDoubleClick(MouseEvent e)
	{
		if (onDoubleClickOffer == null || e == null || SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger())
		{
			return;
		}
		int idx = hitTestSlotIndex(e);
		if (idx < 0 || idx >= slots.size())
		{
			return;
		}
		AlbumSlot slot = slots.get(idx);
		if (slot == null || !slot.ownedAny())
		{
			return;
		}
		onDoubleClickOffer.accept(slot);
	}

	void setSlots(List<AlbumSlot> next)
	{
		setSlots(next, -1);
	}

	void setSlots(List<AlbumSlot> next, int preserveSelectedIndex)
	{
		this.slots = next == null ? Collections.emptyList() : new ArrayList<>(next);
		if (preserveSelectedIndex >= 0 && preserveSelectedIndex < slots.size())
		{
			selectedIndex = preserveSelectedIndex;
		}
		else
		{
			selectedIndex = -1;
		}
		invalidateFaceRasters();
		// Same-page refreshes (lock toggles, pulls) keep their fly-by art; left pages drop.
		transientArt.keySet().retainAll(normalizedUrls(slots));
		requestFlyByArt(slotsGen.incrementAndGet());
		repaint();
		onSelectionChanged.run();
	}

	private Set<String> normalizedUrls(List<AlbumSlot> forSlots)
	{
		Set<String> urls = new HashSet<>();
		for (AlbumSlot slot : forSlots)
		{
			CardDefinition card = slot == null ? null : slot.card();
			String url = card == null ? null : card.getImageUrl();
			if (url != null && !url.isEmpty())
			{
				urls.add(imageCacheService.normalizeImageUrl(url));
			}
		}
		return urls;
	}

	/**
	 * Fly-by tier: a page applied mid-scroll decodes its disk-cached art immediately —
	 * only network fetches stay settle-gated (in the window's preload path). Art lands in
	 * {@link #transientArt} and re-rasters its face in place.
	 */
	private void requestFlyByArt(long gen)
	{
		for (AlbumSlot slot : slots)
		{
			CardDefinition card = slot == null ? null : slot.card();
			String url = card == null ? null : card.getImageUrl();
			if (url == null || url.isEmpty() || imageCacheService.isInMemory(url))
			{
				continue;
			}
			String normalized = imageCacheService.normalizeImageUrl(url);
			if (transientArt.containsKey(normalized))
			{
				continue;
			}
			imageCacheService.loadDiskOnly(url, () -> gen == slotsGen.get(), image ->
			{
				if (image == null)
				{
					return;
				}
				SwingUtilities.invokeLater(() ->
				{
					if (gen != slotsGen.get())
					{
						return;
					}
					transientArt.put(normalized, image);
					refreshFaceForUrl(normalized);
				});
			});
		}
	}

	boolean hasVisibleFoilCards()
	{
		for (AlbumSlot s : slots)
		{
			if (s != null && s.displayFoil())
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Re-renders just the face(s) whose card art matches the settled URL, swapping each
	 * raster in place — every other face stays on screen untouched.
	 */
	void refreshFaceForUrl(String normalizedUrl)
	{
		if (normalizedUrl == null || normalizedUrl.isEmpty()
			|| slots.isEmpty() || faceRasterW <= 0 || faceRasterH <= 0)
		{
			return;
		}
		final long gen = faceRasterGen.get();
		for (int i = 0; i < slots.size(); i++)
		{
			AlbumSlot slot = slots.get(i);
			CardDefinition card = slot == null ? null : slot.card();
			String url = card == null ? null : card.getImageUrl();
			if (url != null && normalizedUrl.equals(imageCacheService.normalizeImageUrl(url)))
			{
				rasterizeSlotInto(gen, faceRasterW, faceRasterH, i, slot);
			}
		}
	}

	private void invalidateFaceRasters()
	{
		faceRasterGen.incrementAndGet();
		scheduledFaceGen = -1L;
		faceRasters = new BufferedImage[slots.size()];
		prevFaceRasters = new BufferedImage[0];
		faceRasterW = 0;
		faceRasterH = 0;
	}

	private void scheduleFaceRasters(int cW, int cH)
	{
		if (cW <= 0 || cH <= 0 || slots.isEmpty())
		{
			return;
		}
		// Already rasterizing (or done) for this slot list + size — do not cancel in-flight work.
		if (cW == faceRasterW && cH == faceRasterH
			&& faceRasters.length == slots.size()
			&& scheduledFaceGen == faceRasterGen.get())
		{
			return;
		}

		if (faceRasterW > 0 && faceRasters.length == slots.size())
		{
			prevFaceRasters = faceRasters;
		}
		faceRasterW = cW;
		faceRasterH = cH;
		faceRasters = new BufferedImage[slots.size()];
		final long gen = faceRasterGen.incrementAndGet();
		scheduledFaceGen = gen;
		rasterizeSlotsInto(gen, cW, cH, new ArrayList<>(slots));
	}

	/** Rasterizes each slot off-EDT and swaps it into {@link #faceRasters} while {@code gen} is current. */
	private void rasterizeSlotsInto(long gen, int width, int height, List<AlbumSlot> snap)
	{
		for (int i = 0; i < snap.size(); i++)
		{
			rasterizeSlotInto(gen, width, height, i, snap.get(i));
		}
	}

	private void rasterizeSlotInto(long gen, int width, int height, int index, AlbumSlot slot)
	{
		faceRasterExecutor.execute(() ->
		{
			if (gen != faceRasterGen.get())
			{
				return;
			}
			BufferedImage raster = rasterizeFace(slot, width, height);
			SwingUtilities.invokeLater(() ->
			{
				if (gen != faceRasterGen.get())
				{
					return;
				}
				if (index >= faceRasters.length)
				{
					return;
				}
				faceRasters[index] = raster;
				scheduleCoalescedRepaint();
			});
		});
	}

	private void scheduleCoalescedRepaint()
	{
		if (!faceRepaintScheduled.compareAndSet(false, true))
		{
			return;
		}
		SwingUtilities.invokeLater(() ->
		{
			faceRepaintScheduled.set(false);
			repaint();
		});
	}

	/** Memory-cached art first, then fly-by art held for the current page. Any-thread safe. */
	private BufferedImage slotArt(String url)
	{
		if (url == null || url.isEmpty())
		{
			return null;
		}
		BufferedImage art = imageCacheService.getIfPresent(url);
		return art != null ? art : transientArt.get(imageCacheService.normalizeImageUrl(url));
	}

	private BufferedImage rasterizeFace(AlbumSlot slot, int cW, int cH)
	{
		BufferedImage raster = new BufferedImage(cW, cH, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g2 = raster.createGraphics();
		try
		{
			CardDefinition card = slot == null ? null : slot.card();
			drawStaticFace(g2, new Rectangle(0, 0, cW, cH), slot,
				slotArt(card == null ? null : card.getImageUrl()));
		}
		finally
		{
			g2.dispose();
		}
		return raster;
	}

	/** Static face only — animated foil overlays are drawn on the EDT blit path. */
	private static void drawStaticFace(Graphics2D g2, Rectangle bounds, AlbumSlot slot, BufferedImage art)
	{
		CardDefinition card = slot == null ? null : slot.card();
		Color rarity = slot == null ? Color.WHITE : slot.rarityColor();
		boolean foil = slot != null && slot.displayFoil();
		boolean owned = slot != null && slot.ownedAny();
		boolean foilScoreLabel = owned && foil;
		Composite prev = g2.getComposite();
		if (!owned)
		{
			g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.3f));
		}
		SharedCardRenderer.drawCardFace(g2, bounds, card, foil, rarity, art, 0L, foilScoreLabel, false);
		if (slot != null && slot.lockBadge())
		{
			SharedCardRenderer.drawLockBadge(g2, bounds);
		}
		g2.setComposite(prev);
	}

	@Override
	public String getToolTipText(MouseEvent event)
	{
		if (event == null)
		{
			return null;
		}
		for (int i = 0; i < lastCardBounds.size() && i < slots.size(); i++)
		{
			Rectangle r = lastCardBounds.get(i);
			if (r != null && r.contains(event.getPoint()))
			{
				AlbumSlot slot = slots.get(i);
				if (slot == null || !slot.ownedAny())
				{
					return null;
				}
				String tip = slot.singleCopyHoverTooltip();
				return tip == null || tip.isEmpty() ? null : tip;
			}
		}
		return null;
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		super.paintComponent(g);
		Graphics2D g2 = (Graphics2D) g.create();
		List<Rectangle> paintedBounds = new ArrayList<>();
		try
		{
			int w = getWidth();
			int h = getHeight();
			if (w <= 0 || h <= 0)
			{
				lastCardBounds = Collections.emptyList();
				return;
			}

			Insets ins = getInsets();
			int innerW = Math.max(0, w - ins.left - ins.right);
			int innerH = Math.max(0, h - ins.top - ins.bottom);
			if (innerW <= 0 || innerH <= 0)
			{
				lastCardBounds = Collections.emptyList();
				return;
			}

			if (slots.isEmpty())
			{
				g2.setColor(new Color(0xAAAAAA));
				g2.drawString("No cards match the current filters.", ins.left + 16, ins.top + 24);
				lastCardBounds = Collections.emptyList();
				return;
			}

			int cellW = (innerW - (COLS - 1) * GAP) / COLS;
			int cellH = (innerH - (ROWS - 1) * GAP) / ROWS;
			int contentH = Math.max(1, cellH - QTY_LABEL_RESERVE_PX);
			double scale = Math.min(
				cellW / (double) SharedCardRenderer.DEFAULT_CARD_WIDTH,
				contentH / (double) SharedCardRenderer.DEFAULT_CARD_HEIGHT) * 0.94d;
			int cW = Math.max(1, (int) Math.round(SharedCardRenderer.DEFAULT_CARD_WIDTH * scale));
			int cH = Math.max(1, (int) Math.round(SharedCardRenderer.DEFAULT_CARD_HEIGHT * scale));
			scheduleFaceRasters(cW, cH);

			for (int i = 0; i < slots.size() && i < COLS * ROWS; i++)
			{
				int col = i % COLS;
				int row = i / COLS;
				int cx = col * (cellW + GAP);
				int cy = row * (cellH + GAP);
				int ox = cx + (cellW - cW) / 2;
				int oy = cy + (contentH - cH) / 2;
				Rectangle bounds = new Rectangle(ins.left + ox, ins.top + oy, cW, cH);
				paintedBounds.add(bounds);

				AlbumSlot slot = slots.get(i);
				BufferedImage face = i < faceRasters.length ? faceRasters[i] : null;
				if (face != null)
				{
					g2.drawImage(face, bounds.x, bounds.y, null);
				}
				else
				{
					BufferedImage prev = i < prevFaceRasters.length ? prevFaceRasters[i] : null;
					CardDefinition card = slot == null ? null : slot.card();
					BufferedImage art = prev == null ? slotArt(card == null ? null : card.getImageUrl()) : null;
					if (prev != null)
					{
						// Mid-resize: the old raster scale-blits until the new one lands.
						g2.drawImage(prev, bounds.x, bounds.y, bounds.width, bounds.height, null);
					}
					else if (art != null)
					{
						// Warm art paints the same frame the page applies; the raster
						// swaps in underneath on a later frame.
						drawStaticFace(g2, bounds, slot, art);
					}
					else
					{
						g2.setColor(PLACEHOLDER_FACE);
						g2.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 8, 8);
					}
				}

				// Continuous foil sparkles + sheen (sheen is a no-op most of the cycle).
				if (slot != null && slot.displayFoil())
				{
					SharedCardRenderer.drawFoilOverlays(g2, bounds, slot.card(), true);
				}

				String qtyLine = qtyLabel(slot);
				if (!qtyLine.isEmpty())
				{
					g2.setColor(new Color(0xDDDDDD));
					g2.setFont(FontManager.getRunescapeSmallFont());
					int tw = g2.getFontMetrics().stringWidth(qtyLine);
					int tx = ins.left + ox + (cW - tw) / 2;
					int ty = ins.top + oy + cH + g2.getFontMetrics().getAscent() + 2;
					g2.drawString(qtyLine, tx, ty);
				}

				if (slot != null && slot.ownedAny() && slot.offeredInTrade())
				{
					g2.setColor(OFFERED_TRADE_BORDER);
					g2.setStroke(new BasicStroke(2f));
					g2.drawRoundRect(bounds.x - 1, bounds.y - 1, bounds.width + 2, bounds.height + 2, 8, 8);
				}
				else if (slot != null && slot.ownedAny() && selectedIndex == i)
				{
					g2.setColor(SELECTION_BORDER);
					g2.setStroke(new BasicStroke(2f));
					g2.drawRoundRect(bounds.x - 1, bounds.y - 1, bounds.width + 2, bounds.height + 2, 8, 8);
				}
			}
		}
		finally
		{
			g2.dispose();
			lastCardBounds = paintedBounds;
		}
	}

	private static String qtyLabel(AlbumSlot s)
	{
		if (s == null || !s.ownedAny())
		{
			return "";
		}
		int t = s.totalOwnedQty();
		if (t <= 1)
		{
			return "";
		}
		int nf = s.nonFoilQty();
		int ff = s.foilQty();
		if (nf > 0 && ff > 0)
		{
			return ff + "x foil, " + nf + "x normal";
		}
		if (nf > 0)
		{
			return nf + "x normal";
		}
		if (ff > 0)
		{
			return ff + "x foil";
		}
		return "";
	}
}
