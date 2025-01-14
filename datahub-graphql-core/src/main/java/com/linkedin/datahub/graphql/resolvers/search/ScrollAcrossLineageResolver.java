package com.linkedin.datahub.graphql.resolvers.search;

import static com.linkedin.datahub.graphql.resolvers.ResolverUtils.bindArgument;
import static com.linkedin.datahub.graphql.resolvers.search.SearchUtils.*;

import com.linkedin.common.urn.Urn;
import com.linkedin.datahub.graphql.QueryContext;
import com.linkedin.datahub.graphql.generated.AndFilterInput;
import com.linkedin.datahub.graphql.generated.EntityType;
import com.linkedin.datahub.graphql.generated.FacetFilterInput;
import com.linkedin.datahub.graphql.generated.LineageDirection;
import com.linkedin.datahub.graphql.generated.ScrollAcrossLineageInput;
import com.linkedin.datahub.graphql.generated.ScrollAcrossLineageResults;
import com.linkedin.datahub.graphql.resolvers.EntityTypeMapper;
import com.linkedin.datahub.graphql.resolvers.ResolverUtils;
import com.linkedin.datahub.graphql.types.mappers.UrnScrollAcrossLineageResultsMapper;
import com.linkedin.entity.client.EntityClient;
import com.linkedin.metadata.query.SearchFlags;
import com.linkedin.r2.RemoteInvocationException;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Resolver responsible for resolving 'searchAcrossEntities' field of the Query type */
@Slf4j
@RequiredArgsConstructor
public class ScrollAcrossLineageResolver
    implements DataFetcher<CompletableFuture<ScrollAcrossLineageResults>> {

  private static final int DEFAULT_COUNT = 10;

  private final EntityClient _entityClient;

  @Override
  public CompletableFuture<ScrollAcrossLineageResults> get(DataFetchingEnvironment environment)
      throws URISyntaxException {
    final ScrollAcrossLineageInput input =
        bindArgument(environment.getArgument("input"), ScrollAcrossLineageInput.class);

    final QueryContext context = environment.getContext();
    final Urn urn = Urn.createFromString(input.getUrn());

    final LineageDirection lineageDirection = input.getDirection();

    List<EntityType> entityTypes =
        (input.getTypes() == null || input.getTypes().isEmpty())
            ? SEARCHABLE_ENTITY_TYPES
            : input.getTypes();
    List<String> entityNames =
        entityTypes.stream().map(EntityTypeMapper::getName).collect(Collectors.toList());

    // escape forward slash since it is a reserved character in Elasticsearch
    final String sanitizedQuery =
        input.getQuery() != null ? ResolverUtils.escapeForwardSlash(input.getQuery()) : null;

    final String scrollId = input.getScrollId() != null ? input.getScrollId() : null;
    final int count = input.getCount() != null ? input.getCount() : DEFAULT_COUNT;
    final List<AndFilterInput> filters =
        input.getOrFilters() != null ? input.getOrFilters() : new ArrayList<>();
    final List<FacetFilterInput> facetFilters =
        filters.stream()
            .map(AndFilterInput::getAnd)
            .flatMap(List::stream)
            .collect(Collectors.toList());
    final Integer maxHops = getMaxHops(facetFilters);
    String keepAlive = input.getKeepAlive() != null ? input.getKeepAlive() : "5m";

    @Nullable
    final Long startTimeMillis =
        input.getStartTimeMillis() == null ? null : input.getStartTimeMillis();
    @Nullable
    final Long endTimeMillis = input.getEndTimeMillis() == null ? null : input.getEndTimeMillis();

    com.linkedin.metadata.graph.LineageDirection resolvedDirection =
        com.linkedin.metadata.graph.LineageDirection.valueOf(lineageDirection.toString());
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            log.debug(
                "Executing search across relationships: source urn {}, direction {}, entity types {}, query {}, filters: {}, start: {}, count: {}",
                urn,
                resolvedDirection,
                input.getTypes(),
                input.getQuery(),
                filters,
                scrollId,
                count);

            SearchFlags searchFlags = null;
            final com.linkedin.datahub.graphql.generated.SearchFlags inputFlags =
                input.getSearchFlags();
            if (inputFlags != null) {
              searchFlags =
                  new SearchFlags()
                      .setSkipCache(inputFlags.getSkipCache())
                      .setFulltext(inputFlags.getFulltext())
                      .setMaxAggValues(inputFlags.getMaxAggValues());
            }
            return UrnScrollAcrossLineageResultsMapper.map(
                _entityClient.scrollAcrossLineage(
                    urn,
                    resolvedDirection,
                    entityNames,
                    sanitizedQuery,
                    maxHops,
                    ResolverUtils.buildFilter(facetFilters, input.getOrFilters()),
                    null,
                    scrollId,
                    keepAlive,
                    count,
                    startTimeMillis,
                    endTimeMillis,
                    searchFlags,
                    ResolverUtils.getAuthentication(environment)));
          } catch (RemoteInvocationException e) {
            log.error(
                "Failed to execute scroll across relationships: source urn {}, direction {}, entity types {}, query {}, filters: {}, start: {}, count: {}",
                urn,
                resolvedDirection,
                input.getTypes(),
                input.getQuery(),
                filters,
                scrollId,
                count);
            throw new RuntimeException(
                "Failed to execute scroll across relationships: "
                    + String.format(
                        "source urn %s, direction %s, entity types %s, query %s, filters: %s, start: %s, count: %s",
                        urn,
                        resolvedDirection,
                        input.getTypes(),
                        input.getQuery(),
                        filters,
                        scrollId,
                        count),
                e);
          }
        });
  }
}
