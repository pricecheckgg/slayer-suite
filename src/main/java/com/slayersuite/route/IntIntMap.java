package com.slayersuite.route;

import java.util.Arrays;

/** An int to int map with open addressing, for the search's hundreds of thousands of tiles without boxing. */
final class IntIntMap
{
	private static final int EMPTY = Integer.MIN_VALUE;
	private int[] keys;
	private int[] values;
	private int size;
	private int limit;

	IntIntMap(int expected)
	{
		int cap = Integer.highestOneBit(Math.max(16, expected * 2) - 1) << 1;
		keys = new int[cap];
		values = new int[cap];
		Arrays.fill(keys, EMPTY);
		limit = cap / 2;
	}

	private static int mix(int k)
	{
		k *= 0x9E3779B1;
		return k ^ (k >>> 16);
	}

	int get(int key, int absent)
	{
		int mask = keys.length - 1;
		int i = mix(key) & mask;
		while (true)
		{
			int k = keys[i];
			if (k == key)
			{
				return values[i];
			}
			if (k == EMPTY)
			{
				return absent;
			}
			i = (i + 1) & mask;
		}
	}

	void put(int key, int value)
	{
		if (size >= limit)
		{
			grow();
		}
		int mask = keys.length - 1;
		int i = mix(key) & mask;
		while (true)
		{
			int k = keys[i];
			if (k == key)
			{
				values[i] = value;
				return;
			}
			if (k == EMPTY)
			{
				keys[i] = key;
				values[i] = value;
				size++;
				return;
			}
			i = (i + 1) & mask;
		}
	}

	int size()
	{
		return size;
	}

	private void grow()
	{
		int[] oldKeys = keys;
		int[] oldValues = values;
		keys = new int[oldKeys.length * 2];
		values = new int[oldKeys.length * 2];
		Arrays.fill(keys, EMPTY);
		limit = keys.length / 2;
		size = 0;
		for (int i = 0; i < oldKeys.length; i++)
		{
			if (oldKeys[i] != EMPTY)
			{
				put(oldKeys[i], oldValues[i]);
			}
		}
	}
}
