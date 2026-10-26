package com.slayersuite.route;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import net.runelite.api.Constants;

/**
 * Every walkable tile of the game, decoded from the Shortest Path project's collision map
 * (BSD 2-Clause, see THIRD-PARTY-NOTICES.md; the decoding follows its SplitFlagMap and
 * CollisionMap). Each tile carries four bits per plane: whether its north and east edges can be
 * crossed, and whether a wall or door sits on those edges. A tile with no open edge is solid.
 */
public final class WorldMap
{
	private static final String RESOURCE = "/com/slayersuite/route/collision-map.zip";
	private static final int REGION_SIZE = Constants.REGION_SIZE;
	private static final int FLAG_NORTH = 0;
	private static final int FLAG_EAST = 1;
	private static final int FLAG_COUNT = 4;
	private static final int BITS_PER_PLANE = REGION_SIZE * REGION_SIZE * FLAG_COUNT;
	private static final int WORDS_PER_PLANE = BITS_PER_PLANE / Long.SIZE;
	private static final int REGION_MASK = REGION_SIZE - 1;
	private static final int[] DX = {0, 1, 1, 1, 0, -1, -1, -1};
	private static final int[] DY = {1, 1, 0, -1, -1, -1, 0, 1};

	private final int minRegionX;
	private final int minRegionY;
	private final int regionsWide;
	private final int regionsHigh;
	private final byte[] planeCounts;
	private final int[] wordOffset;
	private final long[] words;
	private final int regionCount;

	private WorldMap(Map<Integer, byte[]> regions, int minX, int minY, int maxX, int maxY)
	{
		minRegionX = minX;
		minRegionY = minY;
		regionsWide = maxX - minX + 1;
		regionsHigh = maxY - minY + 1;
		int slots = regionsWide * regionsHigh;
		planeCounts = new byte[slots];
		wordOffset = new int[slots];
		Arrays.fill(wordOffset, -1);
		Map<Integer, long[]> decoded = new HashMap<>(regions.size());
		int total = 0;
		for (Map.Entry<Integer, byte[]> e : regions.entrySet())
		{
			int index = index(e.getKey() & 0xFFFF, (e.getKey() >>> 16) & 0xFFFF);
			BitSet bits = BitSet.valueOf(e.getValue());
			int planes = (bits.size() + BITS_PER_PLANE - 1) / BITS_PER_PLANE;
			planeCounts[index] = (byte) planes;
			wordOffset[index] = total;
			decoded.put(index, bits.toLongArray());
			total += planes * WORDS_PER_PLANE;
		}
		words = new long[total];
		for (Map.Entry<Integer, long[]> e : decoded.entrySet())
		{
			long[] w = e.getValue();
			System.arraycopy(w, 0, words, wordOffset[e.getKey()], w.length);
		}
		regionCount = regions.size();
	}

	/** Reads the bundled map; about a second and ten megabytes of heap. */
	public static WorldMap load()
	{
		InputStream in = WorldMap.class.getResourceAsStream(RESOURCE);
		if (in == null)
		{
			throw new IllegalStateException("missing " + RESOURCE);
		}
		return load(in);
	}

	public static WorldMap load(InputStream source)
	{
		Map<Integer, byte[]> regions = new HashMap<>();
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxX = 0;
		int maxY = 0;
		try (ZipInputStream in = new ZipInputStream(source))
		{
			ZipEntry entry;
			byte[] buf = new byte[8192];
			while ((entry = in.getNextEntry()) != null)
			{
				String[] n = entry.getName().split("_");
				if (n.length != 2)
				{
					continue;
				}
				int x = Integer.parseInt(n[0]);
				int y = Integer.parseInt(n[1]);
				minX = Math.min(minX, x);
				minY = Math.min(minY, y);
				maxX = Math.max(maxX, x);
				maxY = Math.max(maxY, y);
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				int read;
				while ((read = in.read(buf)) > 0)
				{
					out.write(buf, 0, read);
				}
				regions.put((x & 0xFFFF) | ((y & 0xFFFF) << 16), out.toByteArray());
			}
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
		if (regions.isEmpty())
		{
			throw new IllegalStateException("empty collision map");
		}
		return new WorldMap(regions, minX, minY, maxX, maxY);
	}

	public int regionCount()
	{
		return regionCount;
	}

	private int index(int regionX, int regionY)
	{
		int rx = regionX - minRegionX;
		int ry = regionY - minRegionY;
		if (rx < 0 || ry < 0 || rx >= regionsWide || ry >= regionsHigh)
		{
			return -1;
		}
		return rx + ry * regionsWide;
	}

	private boolean flag(int x, int y, int z, int flag)
	{
		if (x < 0 || y < 0 || z < 0)
		{
			return false;
		}
		int index = index(x / REGION_SIZE, y / REGION_SIZE);
		if (index < 0)
		{
			return false;
		}
		int offset = wordOffset[index];
		if (offset < 0 || z >= planeCounts[index])
		{
			return false;
		}
		int bit = (z * REGION_SIZE * REGION_SIZE + (y & REGION_MASK) * REGION_SIZE + (x & REGION_MASK)) * FLAG_COUNT + flag;
		return (words[offset + (bit >> 6)] >>> (bit & 63) & 1L) != 0L;
	}

	boolean n(int x, int y, int z)
	{
		return flag(x, y, z, FLAG_NORTH);
	}

	boolean s(int x, int y, int z)
	{
		return n(x, y - 1, z);
	}

	boolean e(int x, int y, int z)
	{
		return flag(x, y, z, FLAG_EAST);
	}

	boolean w(int x, int y, int z)
	{
		return e(x - 1, y, z);
	}

	private boolean ne(int x, int y, int z)
	{
		return n(x, y, z) && e(x, y + 1, z) && e(x, y, z) && n(x + 1, y, z);
	}

	private boolean nw(int x, int y, int z)
	{
		return n(x, y, z) && w(x, y + 1, z) && w(x, y, z) && n(x - 1, y, z);
	}

	private boolean se(int x, int y, int z)
	{
		return s(x, y, z) && e(x, y - 1, z) && e(x, y, z) && s(x + 1, y, z);
	}

	private boolean sw(int x, int y, int z)
	{
		return s(x, y, z) && w(x, y - 1, z) && w(x, y, z) && s(x - 1, y, z);
	}

	/** No edge of the tile can be crossed: solid, or outside the map. */
	public boolean isBlocked(int x, int y, int z)
	{
		return !n(x, y, z) && !s(x, y, z) && !e(x, y, z) && !w(x, y, z);
	}

	public boolean isBlocked(int packed)
	{
		return isBlocked(Packed.x(packed), Packed.y(packed), Packed.plane(packed));
	}

	/**
	 * The tiles one step away that can be walked to. A diagonal is open only when both cardinal
	 * steps beside it are. From a solid tile (standing inside an object) any open tile beside
	 * it counts, so a route can always leave.
	 */
	public int[] neighbours(int packed)
	{
		int x = Packed.x(packed);
		int y = Packed.y(packed);
		int z = Packed.plane(packed);
		if (!isBlocked(x, y, z))
		{
			int mask = 0;
			if (n(x, y, z))
			{
				mask |= 1;
			}
			if (ne(x, y, z))
			{
				mask |= 1 << 1;
			}
			if (e(x, y, z))
			{
				mask |= 1 << 2;
			}
			if (se(x, y, z))
			{
				mask |= 1 << 3;
			}
			if (s(x, y, z))
			{
				mask |= 1 << 4;
			}
			if (sw(x, y, z))
			{
				mask |= 1 << 5;
			}
			if (w(x, y, z))
			{
				mask |= 1 << 6;
			}
			if (nw(x, y, z))
			{
				mask |= 1 << 7;
			}
			int[] out = new int[Integer.bitCount(mask)];
			int k = 0;
			for (int bit = 0; bit < 8; bit++)
			{
				if ((mask & (1 << bit)) != 0)
				{
					out[k++] = Packed.of(x + DX[bit], y + DY[bit], z);
				}
			}
			return out;
		}
		int[] out = new int[8];
		int k = 0;
		for (int dx = -1; dx <= 1; dx++)
		{
			for (int dy = -1; dy <= 1; dy++)
			{
				if ((dx == 0 && dy == 0) || isBlocked(x + dx, y + dy, z))
				{
					continue;
				}
				if (dx == 0 || dy == 0 || (!isBlocked(x + dx, y, z) && !isBlocked(x, y + dy, z)))
				{
					out[k++] = Packed.of(x + dx, y + dy, z);
				}
			}
		}
		return Arrays.copyOf(out, k);
	}
}
