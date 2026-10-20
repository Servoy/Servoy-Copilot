package com.servoy.eclipse.developer.mcp.plainjunit.services;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.servoy.eclipse.developer.mcp.services.FormLocatorService;
import com.servoy.eclipse.developer.mcp.services.FormLocatorService.Resolution;
import com.servoy.eclipse.developer.mcp.services.FormLocatorService.ScoredName;

class FormLocatorServiceTest
{
	private static ScoredName sn(String name, int tier, int wordMatches)
	{
		return new ScoredName(name, tier, wordMatches);
	}

	private static List<String> names(Resolution resolution)
	{
		List<String> names = new ArrayList<>();
		resolution.candidates().forEach(c -> names.add(c.name()));
		return names;
	}

	@Test
	@DisplayName("locate rejects a null or blank query with the exact error before touching the model")
	void locate_blankQuery_returnsError() throws Exception
	{
		FormLocatorService service = new FormLocatorService();
		assertAll(() -> assertEquals("Error: query parameter is required", service.locate(null, true)),
			() -> assertEquals("Error: query parameter is required", service.locate("", true)),
			() -> assertEquals("Error: query parameter is required", service.locate("   ", false)));
	}

	@Test
	@DisplayName("no candidates is notFound with an empty candidate list")
	void resolve_empty()
	{
		Resolution resolution = FormLocatorService.resolve(List.of());

		assertAll(() -> assertEquals(FormLocatorService.STATUS_NOT_FOUND, resolution.status()), () -> assertEquals(0, resolution.candidates().size()),
			() -> assertNull(resolution.resolvedForm()));
	}

	@Test
	@DisplayName("a null ranking is notFound")
	void resolve_null()
	{
		Resolution resolution = FormLocatorService.resolve(null);

		assertAll(() -> assertEquals("notFound", resolution.status()), () -> assertEquals(0, resolution.candidates().size()));
	}

	@Test
	@DisplayName("a single candidate is resolved")
	void resolve_single()
	{
		Resolution resolution = FormLocatorService.resolve(List.of(sn("orders", 2, 0)));

		assertAll(() -> assertEquals("resolved", resolution.status()), () -> assertEquals(List.of("orders"), names(resolution)),
			() -> assertEquals("orders", resolution.resolvedForm().name()));
	}

	@Test
	@DisplayName("a strictly higher tier wins and the loser stays in the candidates")
	void resolve_higherTierWins()
	{
		Resolution resolution = FormLocatorService.resolve(List.of(sn("exact", 3, 0), sn("contains", 2, 0)));

		assertAll(() -> assertEquals("resolved", resolution.status()), () -> assertEquals(List.of("exact", "contains"), names(resolution)),
			() -> assertEquals("exact", resolution.resolvedForm().name()));
	}

	@Test
	@DisplayName("equal tier but strictly more word matches wins")
	void resolve_moreWordMatchesWins()
	{
		Resolution resolution = FormLocatorService.resolve(List.of(sn("two_words", 0, 2), sn("one_word", 0, 1)));

		assertAll(() -> assertEquals("resolved", resolution.status()), () -> assertEquals("two_words", resolution.resolvedForm().name()));
	}

	@Test
	@DisplayName("two equal top (tier, wordMatches) is ambiguous with exactly the tied candidates and no resolved form")
	void resolve_tieIsAmbiguous()
	{
		Resolution resolution = FormLocatorService.resolve(List.of(sn("a", 2, 0), sn("b", 2, 0), sn("c", 1, 0)));

		assertAll(() -> assertEquals("ambiguous", resolution.status()), () -> assertEquals(List.of("a", "b"), names(resolution)),
			() -> assertNull(resolution.resolvedForm()));
	}

	@Test
	@DisplayName("a tie on tier with different word matches is not ambiguous")
	void resolve_sameTierDifferentWordsIsNotATie()
	{
		Resolution resolution = FormLocatorService.resolve(List.of(sn("a", 0, 2), sn("b", 0, 1)));

		assertEquals("resolved", resolution.status());
	}

	@Test
	@DisplayName("three word matches do not tie-collide with an exact-name match")
	void resolve_threeWordMatchesDoNotCollideWithExact()
	{
		Resolution resolution = FormLocatorService.resolve(List.of(sn("exact_name", 3, 0), sn("three_words", 0, 3)));

		assertAll(() -> assertEquals("resolved", resolution.status()), () -> assertEquals("exact_name", resolution.resolvedForm().name()),
			() -> assertEquals(List.of("exact_name", "three_words"), names(resolution)));
	}

	@Test
	@DisplayName("a datasource/comment tier match beats any number of word matches")
	void resolve_tierBeatsWordMatches()
	{
		Resolution resolution = FormLocatorService.resolve(List.of(sn("by_comment", 1, 0), sn("by_words", 0, 3)));

		assertAll(() -> assertEquals("resolved", resolution.status()), () -> assertEquals("by_comment", resolution.resolvedForm().name()));
	}

	@Test
	@DisplayName("the resolved candidate list is capped at 5 in rank order")
	void resolve_resolvedCandidatesCapped()
	{
		List<ScoredName> ranked = List.of(sn("n1", 3, 0), sn("n2", 2, 0), sn("n3", 1, 0), sn("n4", 0, 5), sn("n5", 0, 4), sn("n6", 0, 3), sn("n7", 0, 2));

		Resolution resolution = FormLocatorService.resolve(ranked);

		assertAll(() -> assertEquals("resolved", resolution.status()), () -> assertEquals(List.of("n1", "n2", "n3", "n4", "n5"), names(resolution)));
	}

	@Test
	@DisplayName("the ambiguous candidate list is capped at 5 in rank order")
	void resolve_ambiguousCandidatesCapped()
	{
		List<ScoredName> ranked = List.of(sn("n1", 2, 0), sn("n2", 2, 0), sn("n3", 2, 0), sn("n4", 2, 0), sn("n5", 2, 0), sn("n6", 2, 0), sn("n7", 2, 0));

		Resolution resolution = FormLocatorService.resolve(ranked);

		assertAll(() -> assertEquals("ambiguous", resolution.status()), () -> assertEquals(List.of("n1", "n2", "n3", "n4", "n5"), names(resolution)));
	}

	@Test
	@DisplayName("the maximum number of candidates is 5")
	void maxCandidatesIsFive()
	{
		assertEquals(5, FormLocatorService.MAX_CANDIDATES);
	}
}
