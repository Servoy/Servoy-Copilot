/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 1997-2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.

 This program is distributed in the hope that it will be useful, but WITHOUT
 ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License along
 with this program; if not, see http://www.gnu.org/licenses or write to the Free
 Software Foundation,Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301
*/

package com.servoy.eclipse.opencode.tomcat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.servoy.eclipse.model.util.ServoyLog;

/**
 * Reads a single text file from the Servoy AI workspace (the git root of the
 * active project) for the read-only file reader of the chat UI.
 * <p>
 * This class IS the security boundary of {@code GET /servoy_ai/file}: it only
 * ever returns the content of a regular file whose real path (symlinks,
 * junctions and 8.3 names resolved) lies below the real path of the supplied
 * root, minus the denylist ({@code .git} anywhere, and the Servoy config
 * directory {@code ~/.servoy}). All lexical rules run before any disk access
 * and are platform independent, so their outcome never depends on whether the
 * target exists. The requested path is never percent-decoded here (the servlet
 * container already decoded the query once).
 * </p>
 * <p>
 * Residual risk (TOCTOU): between the final real-path re-check and the actual
 * read, a process with write access to the workspace could swap a path
 * component for a link. The window is narrowed by opening with
 * {@code NOFOLLOW_LINKS} and re-validating the real path after the open, but
 * not closed; exploiting it requires write access to the workspace itself.
 * </p>
 */
public final class WorkspaceFileReader
{
	/** Default maximum file size that is served: 2 MiB. */
	public static final int DEFAULT_MAX_BYTES = 2 * 1024 * 1024;

	private static final int MAX_PATH_LENGTH = 1024;
	private static final int BINARY_SNIFF_BYTES = 8192;
	private static final String FORBIDDEN_CHARS = "<>\"|?*";
	private static final Pattern RESERVED_DEVICE_NAME = Pattern
		.compile("(?i)^(CON|PRN|AUX|NUL|COM[0-9\u00B9\u00B2\u00B3]|LPT[0-9\u00B9\u00B2\u00B3]|CONIN\\$|CONOUT\\$)$");
	private static final Pattern GIT_SHORT_NAME = Pattern.compile("(?i)^git~\\d+$");

	/** Outcome of a read. */
	public enum Status
	{
		OK, BAD_REQUEST, FORBIDDEN, NOT_FOUND, TOO_LARGE, BINARY, ERROR
	}

	/** Immutable read result; the file members are only set for {@link Status#OK}. */
	public static final class Result
	{
		private final Status status;
		private final String path;
		private final String name;
		private final long size;
		private final long modified;
		private final String content;

		private Result(Status status, String path, String name, long size, long modified, String content)
		{
			this.status = status;
			this.path = path;
			this.name = name;
			this.size = size;
			this.modified = modified;
			this.content = content;
		}

		static Result of(Status status)
		{
			return new Result(status, null, null, 0, 0, null);
		}

		static Result ok(String path, String name, long size, long modified, String content)
		{
			return new Result(Status.OK, path, name, size, modified, content);
		}

		public Status getStatus()
		{
			return status;
		}

		/** Workspace-relative path of the real file, {@code /} separated (OK only). */
		public String getPath()
		{
			return path;
		}

		public String getName()
		{
			return name;
		}

		public long getSize()
		{
			return size;
		}

		/** Last modified time in epoch milliseconds. */
		public long getModified()
		{
			return modified;
		}

		public String getContent()
		{
			return content;
		}
	}

	private final Path servoyConfigDir;
	private final int maxBytes;

	/**
	 * @param servoyConfigDir the Servoy config directory ({@code ~/.servoy}) that is never served
	 * @param maxBytes maximum file size in bytes
	 */
	public WorkspaceFileReader(Path servoyConfigDir, int maxBytes)
	{
		this.servoyConfigDir = servoyConfigDir;
		this.maxBytes = maxBytes;
	}

	/** Reader for {@code ~/.servoy} with a 2 MiB limit. */
	public static WorkspaceFileReader createDefault()
	{
		return new WorkspaceFileReader(Path.of(System.getProperty("user.home"), ".servoy"), DEFAULT_MAX_BYTES);
	}

	/**
	 * Reads a workspace file. Never throws for bad input.
	 *
	 * @param root the workspace root (as given by the servlet's project path supplier)
	 * @param requestedPath the requested path as decoded once by the container
	 */
	public Result read(String root, String requestedPath)
	{
		try
		{
			return doRead(root, requestedPath);
		}
		catch (NoSuchFileException e)
		{
			return Result.of(Status.NOT_FOUND);
		}
		catch (InvalidPathException e)
		{
			return Result.of(Status.FORBIDDEN);
		}
		catch (IOException | RuntimeException e)
		{
			// log the exception type only: its message may contain a path, and never any file content
			try
			{
				ServoyLog.logInfo("WorkspaceFileReader: unexpected " + e.getClass().getName() + " while reading a workspace file");
			}
			catch (Throwable t) // NOSONAR logging must not break the "never throws" contract (e.g. no OSGi activator)
			{
				// ignored on purpose
			}
			return Result.of(Status.ERROR);
		}
	}

	private Result doRead(String root, String requestedPath) throws IOException
	{
		if (requestedPath == null || isBlank(requestedPath)) return Result.of(Status.BAD_REQUEST);
		if (root == null || root.isEmpty()) return Result.of(Status.NOT_FOUND);

		List<String> segments = lexicalSegments(root, requestedPath);
		if (segments == null) return Result.of(Status.FORBIDDEN);

		Path rootReal = realRoot(root);
		if (rootReal == null || !Files.isDirectory(rootReal)) return Result.of(Status.NOT_FOUND);

		Path servoyAbs = servoyConfigDir.toAbsolutePath().normalize();
		Path servoyReal = Files.exists(servoyAbs) ? servoyAbs.toRealPath() : servoyAbs;
		if (atOrBelow(rootReal, servoyAbs, servoyReal)) return Result.of(Status.FORBIDDEN);

		Path full = rootReal;
		for (String segment : segments)
		{
			full = full.resolve(segment);
		}
		if (atOrBelow(full.normalize(), servoyAbs, servoyReal)) return Result.of(Status.FORBIDDEN);

		// deepest existing ancestor-or-self must stay inside the root, also when the target is missing
		// (no existence oracle for locations outside the workspace)
		Path probe = full;
		while (!Files.exists(probe))
		{
			// a dangling link (symlink, or a Windows junction/reparse point which is reported as "other")
			// gets the same answer as a link that points outside, so a link cannot be probed for its target
			if (isLinkLike(probe)) return Result.of(Status.FORBIDDEN);
			probe = probe.getParent();
			if (probe == null) return Result.of(Status.FORBIDDEN);
		}
		Path probeReal = probe.toRealPath();
		if (!probeReal.startsWith(rootReal)) return Result.of(Status.FORBIDDEN);
		if (isDenied(rootReal, probeReal, servoyReal)) return Result.of(Status.FORBIDDEN);
		if (!probe.equals(full)) return Result.of(Status.NOT_FOUND);

		Path real = full.toRealPath();
		if (!real.startsWith(rootReal)) return Result.of(Status.FORBIDDEN);
		if (isDenied(rootReal, real, servoyReal)) return Result.of(Status.FORBIDDEN);

		BasicFileAttributes attrs = Files.readAttributes(real, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		if (!attrs.isRegularFile()) return Result.of(Status.NOT_FOUND);

		ByteBuffer buffer = ByteBuffer.allocate(maxBytes + 1);
		try (SeekableByteChannel channel = Files.newByteChannel(real, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
		{
			Path again = real.toRealPath();
			if (!again.equals(real) || !again.startsWith(rootReal)) return Result.of(Status.FORBIDDEN);
			while (buffer.hasRemaining() && channel.read(buffer) >= 0)
			{
				// keep reading until full or EOF
			}
		}
		if (buffer.position() > maxBytes) return Result.of(Status.TOO_LARGE);

		byte[] bytes = new byte[buffer.position()];
		buffer.flip();
		buffer.get(bytes);
		int sniff = Math.min(bytes.length, BINARY_SNIFF_BYTES);
		for (int i = 0; i < sniff; i++)
		{
			if (bytes[i] == 0) return Result.of(Status.BINARY);
		}
		String content;
		try
		{
			content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
		}
		catch (CharacterCodingException e)
		{
			return Result.of(Status.BINARY);
		}
		if (!content.isEmpty() && content.charAt(0) == '\uFEFF') content = content.substring(1);

		StringBuilder relative = new StringBuilder();
		for (Path name : rootReal.relativize(real))
		{
			if (relative.length() > 0) relative.append('/');
			relative.append(name.toString());
		}
		return Result.ok(relative.toString(), real.getFileName().toString(), bytes.length, attrs.lastModifiedTime().toMillis(), content);
	}

	private static boolean isLinkLike(Path path)
	{
		try
		{
			BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			return attrs.isSymbolicLink() || attrs.isOther();
		}
		catch (IOException | RuntimeException e)
		{
			return false;
		}
	}

	private static boolean isBlank(String value)
	{
		for (int i = 0; i < value.length(); i++)
		{
			if (value.charAt(i) != ' ') return false;
		}
		return true;
	}

	private static Path realRoot(String root)
	{
		try
		{
			return Path.of(root).toRealPath();
		}
		catch (IOException | InvalidPathException e)
		{
			return null;
		}
	}

	private static boolean atOrBelow(Path path, Path a, Path b)
	{
		return path.startsWith(a) || path.startsWith(b);
	}

	/**
	 * Applies every lexical rule and returns the relative segments, or {@code null} when the input is forbidden.
	 */
	private static List<String> lexicalSegments(String root, String input)
	{
		if (input.length() > MAX_PATH_LENGTH) return null;
		for (int i = 0; i < input.length(); i++)
		{
			char c = input.charAt(i);
			if (c < 0x20 || c == 0x7F || FORBIDDEN_CHARS.indexOf(c) >= 0) return null;
		}
		if (input.startsWith("\\\\") || input.startsWith("//") || input.startsWith("\\/") || input.startsWith("/\\")) return null;

		Path candidate = Path.of(input);
		boolean separatorStart = input.charAt(0) == '/' || input.charAt(0) == '\\';
		List<String> segments = new ArrayList<>();
		if (candidate.isAbsolute())
		{
			if (!segmentsBelowRoot(candidate, root, segments)) return null;
		}
		else
		{
			if (separatorStart) return null;
			for (String part : input.split("[/\\\\]"))
			{
				if (!part.isEmpty()) segments.add(part);
			}
		}
		if (segments.isEmpty()) return null;
		for (String segment : segments)
		{
			if (!isAllowedSegment(segment)) return null;
		}
		return segments;
	}

	private static boolean segmentsBelowRoot(Path absolute, String root, List<String> out)
	{
		Path rootPath = Path.of(root).toAbsolutePath();
		List<Path> bases = new ArrayList<>();
		bases.add(rootPath);
		bases.add(rootPath.normalize());
		Path real = realRoot(root);
		if (real != null) bases.add(real);
		for (Path base : bases)
		{
			if (absolute.startsWith(base))
			{
				for (int i = base.getNameCount(); i < absolute.getNameCount(); i++)
				{
					out.add(absolute.getName(i).toString());
				}
				return true;
			}
		}
		return false;
	}

	static boolean isAllowedSegment(String segment)
	{
		if (segment.indexOf(':') >= 0) return false;
		if (segment.equals(".") || segment.equals("..")) return false;
		char last = segment.charAt(segment.length() - 1);
		if (last == '.' || last == ' ') return false;
		int dot = segment.indexOf('.');
		String base = dot >= 0 ? segment.substring(0, dot) : segment;
		int end = base.length();
		while (end > 0 && (base.charAt(end - 1) == ' ' || base.charAt(end - 1) == '.'))
		{
			end--;
		}
		if (RESERVED_DEVICE_NAME.matcher(base.substring(0, end)).matches()) return false;
		return !segment.equalsIgnoreCase(".git") && !GIT_SHORT_NAME.matcher(segment).matches();
	}

	/** Denylist on the real path: {@code .git}, the Servoy config dir, and any alias of them. */
	private static boolean isDenied(Path rootReal, Path real, Path servoyReal) throws IOException
	{
		for (Path name : rootReal.relativize(real))
		{
			if (name.toString().equalsIgnoreCase(".git")) return true;
		}
		if (real.startsWith(servoyReal)) return true;
		Path gitDir = rootReal.resolve(".git");
		boolean gitExists = Files.exists(gitDir);
		boolean servoyExists = Files.exists(servoyReal);
		for (Path a = real; a != null && a.startsWith(rootReal) && !a.equals(rootReal); a = a.getParent())
		{
			if (gitExists && Files.isSameFile(a, gitDir)) return true;
			if (servoyExists && Files.isSameFile(a, servoyReal)) return true;
		}
		return false;
	}
}
