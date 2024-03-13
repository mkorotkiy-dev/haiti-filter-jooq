package dev.struchkov.haiti.filter.jooq;

import dev.struchkov.haiti.filter.jooq.exception.FilterJooqHaitiException;
import dev.struchkov.haiti.filter.jooq.join.JoinTable;
import dev.struchkov.haiti.filter.jooq.join.JoinTypeOperation;
import dev.struchkov.haiti.filter.jooq.page.PageableOffset;
import dev.struchkov.haiti.filter.jooq.page.PageableSeek;
import dev.struchkov.haiti.filter.jooq.sort.NullsOrderType;
import dev.struchkov.haiti.filter.jooq.sort.SortContainer;
import dev.struchkov.haiti.filter.jooq.sort.SortType;
import dev.struchkov.haiti.utils.Inspector;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Operator;
import org.jooq.Query;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.SelectConditionStep;
import org.jooq.SelectHavingStep;
import org.jooq.SelectJoinStep;
import org.jooq.SelectLimitStep;
import org.jooq.SelectSeekStepN;
import org.jooq.SelectSelectStep;
import org.jooq.SortField;
import org.jooq.Table;
import org.jooq.impl.DSL;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static dev.struchkov.haiti.filter.jooq.exception.FilterJooqHaitiException.filterJooqException;
import static java.util.stream.Collectors.toList;
import static org.jooq.impl.DSL.condition;
import static org.jooq.impl.DSL.field;

public class CriteriaJooqFilter {

    private final List<Condition> andConditions = new ArrayList<>();
    private final List<Condition> orConditions = new ArrayList<>();
    private final List<JoinTable> joinTables = new ArrayList<>();

    private final Table<Record> generalTable;
    private final DSLContext dsl;

    private final List<SortContainer> sorts = new ArrayList<>();
    private PageableOffset offset;
    private PageableSeek seek;
    private final List<String> groupByFields = new ArrayList<>();

    protected CriteriaJooqFilter(String table, DSLContext dsl) {
        this.generalTable = DSL.table(table);
        this.dsl = dsl;
    }

    public static CriteriaJooqFilter create(String table, DSLContext dsl) {
        return new CriteriaJooqFilter(table, dsl);
    }

    public CriteriaJooqFilter and(CriteriaJooqQuery filterQuery) {
        generateAnd(filterQuery);
        return this;
    }

    public CriteriaJooqFilter and(Consumer<CriteriaJooqQuery> query) {
        final CriteriaJooqQuery criteriaQuery = CriteriaJooqQuery.create();
        query.accept(criteriaQuery);
        generateAnd(criteriaQuery);
        return this;
    }

    public CriteriaJooqFilter or(CriteriaJooqQuery filterQuery) {
        generateOr(filterQuery);
        return this;
    }

    public CriteriaJooqFilter or(Consumer<CriteriaJooqQuery> query) {
        final CriteriaJooqQuery criteriaQuery = CriteriaJooqQuery.create();
        query.accept(criteriaQuery);
        generateOr(criteriaQuery);
        return this;
    }

    public CriteriaJooqFilter page(PageableOffset offset) {
        Inspector.isNotNull(offset);
        Inspector.isNull(seek, filterJooqException("Нельзя установить два типа пагинации одновременно"));
        this.offset = offset;
        return this;
    }

    public CriteriaJooqFilter page(PageableSeek seek) {
        Inspector.isNotNull(seek);
        Inspector.isNull(offset, filterJooqException("Нельзя установить два типа пагинации одновременно"));
        this.seek = seek;
        return this;
    }

    public CriteriaJooqFilter sort(SortContainer container) {
        if (container != null && container.getFieldName() != null) {
            this.sorts.add(container);
        }
        return this;
    }

    public CriteriaJooqFilter sort(String field, SortType sortType, NullsOrderType nullsOrder) {
        if (field != null) {
            this.sorts.add(SortContainer.of(field, sortType, nullsOrder));
        }
        return this;
    }

    public CriteriaJooqFilter sort(String field, SortType sortType) {
        if (field != null) {
            this.sorts.add(SortContainer.of(field, sortType));
        }
        return this;
    }

    public CriteriaJooqFilter sort(String field) {
        if (field != null) {
            this.sorts.add(SortContainer.of(field));
        }
        return this;
    }

    public CriteriaJooqFilter groupBy(String field) {
        if (field != null) {
            groupByFields.add(field);
        }
        return this;
    }

    public CriteriaJooqFilter groupBy(String... fields) {
        if (fields != null && fields.length > 0) {
            groupByFields.addAll(Arrays.asList(fields));
        }
        return this;
    }

    public CriteriaJooqFilter groupBy(Collection<String> fields) {
        if (fields != null && !fields.isEmpty()) {
            groupByFields.addAll(fields);
        }
        return this;
    }

    public CriteriaJooqFilter join(JoinTable... joinTables) {
        Inspector.isNotNull(joinTables);
        this.joinTables.addAll(Arrays.stream(joinTables).collect(toList()));
        return this;
    }

    private void generateAnd(CriteriaJooqQuery criteriaQuery) {
        andConditions.addAll(criteriaQuery.getConditions());
    }

    private void generateOr(CriteriaJooqQuery criteriaQuery) {
        orConditions.addAll(criteriaQuery.getConditions());
    }

    public Query generateQuery(String... fields) {
        final List<Field<Object>> selectFields = Arrays.stream(fields).map(DSL::field).collect(toList());
        final SelectSelectStep<Record> mainSelect = !selectFields.isEmpty() ? dsl.select(selectFields) : dsl.select();
        final SelectLimitStep<? extends Record> selectSeekStepN = generate(mainSelect);
        return setPaginationOffset(selectSeekStepN);
    }

    public Query generateCount() {
        final SelectSelectStep<Record1<Integer>> selectCount = dsl.selectCount();
        return generate(selectCount);
    }

    private SelectLimitStep<? extends Record> generate(SelectSelectStep<? extends Record> mainSelect) {
        final SelectJoinStep<? extends Record> from = mainSelect.from(generalTable);
        final SelectJoinStep<? extends Record> join = joinTables(from);
        final SelectConditionStep<? extends Record> where = join.where(getConditions());
        final SelectHavingStep<? extends Record> groupBy = getGroupBy(where);
        final SelectLimitStep<? extends Record> orderBy = getOrderBy(groupBy);
        return orderBy;
    }

    private SelectLimitStep<? extends Record> getOrderBy(SelectHavingStep<? extends Record> groupBy) {
        if (!sorts.isEmpty()) {
            final SelectSeekStepN<? extends Record> sort = groupBy.orderBy(getOrderBy());
            setPaginationSeek(sort);
            return sort;
        }
        return groupBy;
    }

    private SelectHavingStep<? extends Record> getGroupBy(SelectConditionStep<? extends Record> where) {
        if (!groupByFields.isEmpty()) {
            return where.groupBy(
                    groupByFields.stream()
                            .map(DSL::field)
                            .collect(toList())
            );
        }
        return where;
    }

    private SelectJoinStep<? extends Record> joinTables(SelectJoinStep<? extends Record> from) {
        if (!joinTables.isEmpty()) {
            for (JoinTable joinTable : joinTables) {
                final String tableName = joinTable.getTableName();
                String fieldBase = joinTable.getFieldBase();
                String fieldReference = joinTable.getFieldReference();

                Table<Record> dlsJoinTableName = DSL.table(tableName);

                if (joinTable.getAlias() != null) {
                    dlsJoinTableName = dlsJoinTableName.as(joinTable.getAlias());
                    fieldReference = joinTable.getAlias() + "." + fieldReference;
                } else {
                    fieldReference = tableName + "." + fieldReference;
                }

                final JoinTypeOperation joinType = joinTable.getJoinTypeOperation();

                final Field dslFieldBase = field(fieldBase);
                final Field dslFieldReference = field(fieldReference);

                final Condition on = dslFieldBase.eq(dslFieldReference);
                switch (joinType) {
                    case LEFT:
                        from = from.leftJoin(dlsJoinTableName).on(on);
                        break;
                    case INNER:
                        from = from.innerJoin(dlsJoinTableName).on(on);
                        break;
                    case RIGHT:
                        from = from.rightJoin(dlsJoinTableName).on(on);
                        break;
                }
            }
        }
        return from;
    }

    private List<Condition> getConditions() {
        final List<Condition> conditions = new ArrayList<>();
        if (!andConditions.isEmpty()) {
            conditions.add(condition(Operator.AND, andConditions));
        }
        if (!orConditions.isEmpty()) {
            conditions.add(condition(Operator.OR, orConditions));
        }
        return conditions;
    }

    private Collection<SortField<Object>> getOrderBy() {
        if (!sorts.isEmpty()) {
            final List<SortField<Object>> newSorts = new ArrayList<>();
            for (SortContainer sort : sorts) {
                final NullsOrderType nullsOrder = sort.getNullsOrder();
                final SortType sortType = sort.getType();
                final String fieldName = sort.getFieldName();

                final SortField<Object> sortField = SortType.ASC.equals(sortType) ? field(fieldName).asc() : field(fieldName).desc();
                if (NullsOrderType.LAST.equals(nullsOrder)) {
                    newSorts.add(sortField.nullsLast());
                } else {
                    newSorts.add(sortField.nullsFirst());
                }
            }
            return newSorts;
        }
        return Collections.emptyList();
    }

    private void setPaginationSeek(SelectSeekStepN<? extends Record> sort) {
        if (seek != null) {
            Inspector.isNotNull(() -> new FilterJooqHaitiException("При использовании пагинации типа seek необходимо указать сортировку"), sort);
            final Integer pageSize = seek.getPageSize();
            final Object[] values = seek.getValues();
            if (values != null) {
                sort
                        .seek(values)
                        .limit(pageSize);
            } else {
                sort
                        .limit(pageSize);
            }
        }
    }

    private Query setPaginationOffset(SelectLimitStep<? extends Record> selectBuilder) {
        if (offset != null) {
            final int pageNumber = offset.getPageNumber();
            final int pageSize = offset.getPageSize();
            final int offsetNumber = (pageNumber + 1) * pageSize - pageSize;
            return selectBuilder.limit(pageSize).offset(offsetNumber);
        }
        return selectBuilder;
    }

}
