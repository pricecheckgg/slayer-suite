package com.slayersuite;

import com.slayersuite.advisor.ScenePath;
import java.util.List;
import net.runelite.api.CollisionDataFlag;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ScenePathTest
{
	private static int[][] grid(int n)
	{
		return new int[n][n];
	}

	@Test
	public void straightLineWhenOpen()
	{
		List<int[]> p = ScenePath.find(grid(10), 0, 0, 5, 5, 60);
		assertEquals(6, p.size());
		assertEquals(5, p.get(5)[0]);
		assertEquals(5, p.get(5)[1]);
	}

	@Test
	public void routesAroundAWallThroughTheGap()
	{
		int[][] f = grid(10);
		for (int y = 0; y < 10; y++)
		{
			if (y != 8)
			{
				f[5][y] = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
			}
		}
		List<int[]> p = ScenePath.find(f, 2, 2, 8, 2, 100);
		int[] end = p.get(p.size() - 1);
		assertEquals(8, end[0]);
		assertEquals(2, end[1]);
		boolean throughGap = false;
		for (int[] t : p)
		{
			assertTrue("never stands in the wall", !(t[0] == 5 && t[1] != 8));
			throughGap |= t[0] == 5 && t[1] == 8;
		}
		assertTrue(throughGap);
	}

	@Test
	public void unreachableTargetEndsAtNearestReachableTile()
	{
		int[][] f = grid(10);
		for (int y = 0; y < 10; y++)
		{
			f[5][y] = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
		}
		List<int[]> p = ScenePath.find(f, 1, 1, 8, 1, 100);
		int[] end = p.get(p.size() - 1);
		assertEquals(4, end[0]);
		assertEquals(1, end[1]);
	}

	@Test
	public void wallFlagsBlockCardinalStepsAndDiagonalsNeedBothSides()
	{
		int[][] f = grid(4);
		f[1][1] = CollisionDataFlag.BLOCK_MOVEMENT_EAST;
		List<int[]> p = ScenePath.find(f, 1, 1, 2, 1, 10);
		assertTrue("must detour instead of stepping east through the wall", p.size() > 2);
		assertEquals(2, p.get(p.size() - 1)[0]);
	}
}
