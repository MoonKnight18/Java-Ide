package com.pranav.javacompletion.model;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.base.Joiner;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Range;
import com.google.common.collect.RangeMap;
import org.openjdk.source.tree.LineMap;
import org.openjdk.tools.javac.tree.JCTree;
import org.openjdk.tools.javac.tree.JCTree.JCCompilationUnit;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** Scope of entities in the scope of a Java source file. */
public class FileScope implements EntityScope {
    private static final String TYPE_INDEX_SCHEME = "type";
    private static final String TEST_SCHEME = "test";
    private static final Joiner FILE_PATH_JOINER = Joiner.on("/");
    private static final Range<Integer> EMPTY_RANGE = Range.closed(0, 0);

    /** The type of the file that the {@link FileScope} is created from. */
    public enum FileType {
        /**
         * The {@link FileScope} is created from a Java source code file. The filename can be accessed
         * from the file sytstem.
         */
        SOURCE_CODE,
        /**
         * The {@link FileScope} is created from a type index JSON file generated by the Indexer tool.
         * There is no actual file available on the file system.
         */
        TYPE_INDEX,
        /**
         * The {@link FileScope} is created from a compiled .class file. The file name may point to an
         * existing file, or a file in the JAR archive.
         */
        CLASS_FILE,
        /** No file is used for creating the {@link FileScope}. It's useful for testing. */
        NONE,
    }

    private final String filename;
    // Map of simple names -> entities.
    private final Multimap<String, Entity> entities;
    private final ImmutableList<String> packageQualifiers;
    private final Map<String, List<String>> importedClasses;
    private final Map<String, List<String>> importedStaticMembers;
    private final List<List<String>> onDemandClassImportQualifiers;
    private final List<List<String>> onDemandStaticImportQualifiers;
    private final Optional<JCTree.JCCompilationUnit> compilationUnit;
    private final FileType fileType;
    private final Range<Integer> definitionRange;
    private RangeMap<Integer, EntityScope> scopeRangeMap = null;
    private Optional<LineMap> adjustedLineMap = Optional.empty();

    private FileScope(
            String filename,
            List<String> packageQualifiers,
            JCCompilationUnit compilationUnit,
            FileType fileType,
            Range<Integer> definitionRange) {
        checkArgument(
                (compilationUnit != null) == (fileType == FileType.SOURCE_CODE),
                "compilationUnit should be non-null iff fileType is SOURCE_CODE. "
                        + "Actual values: compilationUnit: %s, fileType: %s",
                compilationUnit,
                fileType);
        this.filename = filename;
        this.entities = HashMultimap.create();
        this.packageQualifiers = ImmutableList.copyOf(packageQualifiers);
        this.importedClasses = new HashMap<>();
        this.importedStaticMembers = new HashMap<>();
        this.onDemandClassImportQualifiers = new ArrayList<>();
        this.onDemandStaticImportQualifiers = new ArrayList<>();
        this.compilationUnit = Optional.ofNullable(compilationUnit);
        this.fileType = fileType;
        this.definitionRange = definitionRange;
    }

    public static FileScope createFromSource(
            String filename,
            List<String> packageQualifiers,
            JCCompilationUnit compilationUnit,
            int fileSize) {
        return new FileScope(
                filename,
                packageQualifiers,
                compilationUnit,
                FileType.SOURCE_CODE,
                Range.closed(0, fileSize));
    }

    public static FileScope createFromTypeIndex(List<String> packageQualifiers) {
        String filename = TYPE_INDEX_SCHEME + "://" + FILE_PATH_JOINER.join(packageQualifiers);
        return new FileScope(
                filename, packageQualifiers, null /* compilationUnit */, FileType.TYPE_INDEX, EMPTY_RANGE);
    }

    public static FileScope createFromClassFile(Path classFilePath, List<String> packageQualifiers) {
        return new FileScope(
                classFilePath.toString(),
                packageQualifiers,
                null /* compilationUnit */,
                FileType.CLASS_FILE,
                EMPTY_RANGE);
    }

    public static FileScope createForTesting(List<String> packageQualifiers) {
        String filename = TEST_SCHEME + "://" + FILE_PATH_JOINER.join(packageQualifiers);
        return new FileScope(
                filename, packageQualifiers, null /* compilationUnit */, FileType.NONE, EMPTY_RANGE);
    }

    @Override
    public Multimap<String, Entity> getMemberEntities() {
        return ImmutableMultimap.copyOf(entities);
    }

    public Optional<List<String>> getImportedClass(String simpleName) {
        return Optional.ofNullable(importedClasses.get(simpleName));
    }

    public List<List<String>> getAllImportedClasses() {
        return ImmutableList.copyOf(importedClasses.values());
    }

    public Optional<List<String>> getImportedStaticMember(String simpleName) {
        return Optional.ofNullable(importedStaticMembers.get(simpleName));
    }

    public List<List<String>> getAllImportedStaticMembers() {
        return ImmutableList.copyOf(importedStaticMembers.values());
    }

    public void addImportedClass(List<String> qualifiers) {
        if (qualifiers.isEmpty()) {
            return;
        }
        importedClasses.put(qualifiers.get(qualifiers.size() - 1), qualifiers);
    }

    public void addImportedStaticMembers(List<String> qualifiers) {
        if (qualifiers.isEmpty()) {
            return;
        }
        importedStaticMembers.put(qualifiers.get(qualifiers.size() - 1), qualifiers);
    }

    /**
     * Returns a list of all on-demand class imported qualifiers added by {@link
     * #addOnDemandClassImport}.
     *
     * <p>Similar to {@link #addOnDemandClassImport}, the returned qualifiers do not include the
     * trailing *.
     */
    public List<List<String>> getOnDemandClassImportQualifiers() {
        return ImmutableList.copyOf(onDemandClassImportQualifiers);
    }

    /**
     * Adds an on-demand class import (e.g. {@code import foo.bar.*}).
     *
     * @param qualifiers the imported package qualifiers without *. For example, if the import
     *     statment is {@code import foo.bar.*}, then the qualifiers are {@code ['foo', 'bar']}
     */
    public void addOnDemandClassImport(List<String> qualifiers) {
        if (qualifiers.isEmpty()) {
            return;
        }
        onDemandClassImportQualifiers.add(ImmutableList.copyOf(qualifiers));
    }

    /**
     * Returns a list of all on-demand imported qualifiers for static members added by {@link
     * #addOnDemandStaticImport}.
     *
     * <p>Similar to {@link #addOnDemandStaticImport}, the returned qualifiers do not include the
     * trailing *.
     */
    public List<List<String>> getOnDemandStaticImportQualifiers() {
        return ImmutableList.copyOf(onDemandStaticImportQualifiers);
    }

    /**
     * Adds an on-demand import for static members (e.g. {@code import static foo.Bar.*}).
     *
     * @param qualifiers the imported package qualifiers without *. For example, if the import
     *     statment is {@code import static foo.Bar.*}, then the qualifiers are {@code ['foo', 'Bar']}
     */
    public void addOnDemandStaticImport(List<String> qualifiers) {
        if (qualifiers.isEmpty()) {
            return;
        }
        onDemandStaticImportQualifiers.add(ImmutableList.copyOf(qualifiers));
    }

    @Override
    public void addEntity(Entity entity) {
        entities.put(entity.getSimpleName(), entity);
    }

    @Override
    public void addChildScope(EntityScope entityScope) {
        throw new UnsupportedOperationException(
                "Only classes can be added to a file. Found " + entityScope.getClass().getSimpleName());
    }

    public void setScopeRangeMap(RangeMap<Integer, EntityScope> scopeRangeMap) {
        this.scopeRangeMap = scopeRangeMap;
    }

    public RangeMap<Integer, EntityScope> getScopeRangeMap() {
        return scopeRangeMap;
    }

    public EntityScope getEntityScopeAt(int position) {
        EntityScope scope = scopeRangeMap.get(position);
        if (scope == null) {
            scope = this;
        }
        return scope;
    }

    public List<String> getPackageQualifiers() {
        return packageQualifiers;
    }

    @Override
    public Optional<EntityScope> getParentScope() {
        return Optional.empty();
    }

    public String getFilename() {
        return filename;
    }

    /** @return non-null value iff {@link #getFileType} returns {@code SOURCE_TYPE} */
    public Optional<JCCompilationUnit> getCompilationUnit() {
        return compilationUnit;
    }

    public void setAdjustedLineMap(LineMap adjustedLineMap) {
        this.adjustedLineMap = Optional.of(adjustedLineMap);
    }

    /**
     * Gets the {@link LineMap} for this file.
     *
     * <p>Do not call this method if {@link #getFileType} returns values other than {@code
     * SOURCE_CODE}.
     *
     * <p>Note: use this method instead of {@code getCompilationUnit().getLineMap()}. The line map may
     * need adjustment if the source code is fixed by {@code FileContentFixer}.
     *
     * @throws IllegalStateException thrown if called when the return value of {@link #getFileType} is
     *     not {@code SOURCE_CODE}.
     */
    public Optional<LineMap> getLineMap() {
        if (adjustedLineMap.isPresent()) {
            return adjustedLineMap;
        }
        if (!compilationUnit.isPresent()) {
            return Optional.empty();
        }
        return Optional.of(compilationUnit.get().getLineMap());
    }

    public FileType getFileType() {
        return fileType;
    }

    @Override
    public String toString() {
        return "FileScope<" + getFilename() + ", " + this.packageQualifiers + ">";
    }

    @Override
    public Optional<Entity> getDefiningEntity() {
        return Optional.empty();
    }

    @Override
    public List<EntityScope> getChildScopes() {
        return entities.values().stream()
                .map(Entity::getScope)
                .collect(Collectors.toList());
    }

    @Override
    public Range<Integer> getDefinitionRange() {
        return definitionRange;
    }
}