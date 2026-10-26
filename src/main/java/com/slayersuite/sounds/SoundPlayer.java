package com.slayersuite.sounds;

import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.audio.AudioPlayer;
import net.runelite.client.util.Filepath;

/**
 * Plays the bundled chimes, or a player's own file of the same name from the plugin's
 * sounds folder when one exists. The folder comes from RuneLite's plugin directory API.
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

	private final AudioPlayer audioPlayer;
	private final ScheduledExecutorService executor;
	private volatile long lastPlayedAt;
	/** The plugin's sounds folder; null until the plugin starts, in which case only bundled sounds play. */
	private volatile Filepath directory;

	@Inject
	public SoundPlayer(AudioPlayer audioPlayer, ScheduledExecutorService executor)
	{
		this.audioPlayer = audioPlayer;
		this.executor = executor;
	}

	public void setDirectory(Filepath directory)
	{
		this.directory = directory;
	}

	public Filepath getDirectory()
	{
		return directory;
	}

	private Filepath custom(Sound sound)
	{
		Filepath dir = directory;
		if (dir == null)
		{
			return null;
		}
		Filepath f = dir.joinSegment(sound.file);
		return f.isFile() ? f : null;
	}

	public void play(Sound sound, int volumePercent)
	{
		if (volumePercent <= 0)
		{
			return;
		}
		if (sound == Sound.LEVEL_UP && custom(sound) == null)
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
				Filepath custom = custom(sound);
				if (custom != null)
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
