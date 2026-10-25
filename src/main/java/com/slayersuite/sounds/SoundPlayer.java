package com.slayersuite.sounds;

import java.io.File;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.audio.AudioPlayer;

/**
 * Plays the bundled chimes, or a player's own file of the same name from
 * ~/.runelite/slayer-suite/sounds/ when one exists.
 */
@Slf4j
@Singleton
public class SoundPlayer
{
	public enum Sound
	{
		TASK_DONE("task-done.wav"),
		SUPERIOR("superior.wav"),
		ALERT("alert.wav"),
		TICK("tick.wav"),
		/** No bundled file: plays only when the player supplies level-up.wav. */
		LEVEL_UP("level-up.wav");

		final String file;

		Sound(String file)
		{
			this.file = file;
		}
	}

	public static final File SOUND_DIR = new File(RuneLite.RUNELITE_DIR, "slayer-suite/sounds");

	private final AudioPlayer audioPlayer;
	private final ScheduledExecutorService executor;
	private volatile long lastPlayedAt;

	@Inject
	public SoundPlayer(AudioPlayer audioPlayer, ScheduledExecutorService executor)
	{
		this.audioPlayer = audioPlayer;
		this.executor = executor;
	}

	public void play(Sound sound, int volumePercent)
	{
		if (volumePercent <= 0)
		{
			return;
		}
		if (sound == Sound.LEVEL_UP && !new File(SOUND_DIR, sound.file).isFile())
		{
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastPlayedAt < (sound == Sound.TICK ? 80 : 250))
		{
			return;
		}
		lastPlayedAt = now;
		float gainDb = (float) (20.0 * Math.log10(Math.max(1, Math.min(100, volumePercent)) / 100.0));
		executor.execute(() ->
		{
			try
			{
				File custom = new File(SOUND_DIR, sound.file);
				if (custom.isFile())
				{
					audioPlayer.play(custom, gainDb);
				}
				else
				{
					audioPlayer.play(SoundPlayer.class, "/com/slayersuite/sounds/" + sound.file, gainDb);
				}
			}
			catch (Exception e)
			{
				log.warn("Slayer Suite: could not play {} ({})", sound, e.toString());
			}
		});
	}
}
