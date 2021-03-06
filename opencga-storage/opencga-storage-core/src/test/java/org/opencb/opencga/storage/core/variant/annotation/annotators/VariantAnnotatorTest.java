package org.opencb.opencga.storage.core.variant.annotation.annotators;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.opencb.biodata.models.variant.Variant;
import org.opencb.biodata.models.variant.avro.VariantAnnotation;
import org.opencb.commons.datastore.core.ObjectMap;
import org.opencb.commons.datastore.core.QueryResult;
import org.opencb.opencga.storage.core.config.StorageConfiguration;
import org.opencb.opencga.storage.core.manager.StorageManager;
import org.opencb.opencga.storage.core.variant.annotation.VariantAnnotationManager;
import org.opencb.opencga.storage.core.variant.annotation.VariantAnnotatorException;

import java.util.*;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

/**
 * Created by jacobo on 27/11/17.
 */
public class VariantAnnotatorTest {

    private StorageConfiguration storageConfiguration;

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Before
    public void setUp() throws Exception {
        storageConfiguration = StorageConfiguration.load(StorageManager.class.getClassLoader().getResourceAsStream("storage-configuration.yml"), "yml");
        storageConfiguration.getStorageEngine().getVariant().getOptions().put(VariantAnnotationManager.SPECIES, "hsapiens");
        storageConfiguration.getStorageEngine().getVariant().getOptions().put(VariantAnnotationManager.ASSEMBLY, "grch38");
    }

    @Test
    public void testVariantFactory() throws Exception {
        ObjectMap options = new ObjectMap(VariantAnnotationManager.ANNOTATOR, VariantAnnotatorFactory.AnnotationSource.CELLBASE_DB_ADAPTOR);
        VariantAnnotator variantAnnotator = VariantAnnotatorFactory.buildVariantAnnotator(storageConfiguration, storageConfiguration.getDefaultStorageEngineId(), options);
        assertThat(variantAnnotator, is(instanceOf(CellBaseDirectVariantAnnotator.class)));

        storageConfiguration.getStorageEngine().getVariant().getOptions().put(VariantAnnotationManager.ANNOTATOR, VariantAnnotatorFactory.AnnotationSource.CELLBASE_DB_ADAPTOR.toString());
        options = new ObjectMap();
        variantAnnotator = VariantAnnotatorFactory.buildVariantAnnotator(storageConfiguration, storageConfiguration.getDefaultStorageEngineId(), options);
        assertThat(variantAnnotator, is(instanceOf(CellBaseDirectVariantAnnotator.class)));

        options = new ObjectMap(VariantAnnotationManager.ANNOTATOR, null);
        variantAnnotator = VariantAnnotatorFactory.buildVariantAnnotator(storageConfiguration, storageConfiguration.getDefaultStorageEngineId(), options);
        assertThat(variantAnnotator, is(instanceOf(CellBaseDirectVariantAnnotator.class)));

        storageConfiguration.getStorageEngine().getVariant().getOptions().put(VariantAnnotationManager.ANNOTATOR, VariantAnnotatorFactory.AnnotationSource.CELLBASE_REST);
        options = new ObjectMap(VariantAnnotationManager.ANNOTATOR, null);
        variantAnnotator = VariantAnnotatorFactory.buildVariantAnnotator(storageConfiguration, storageConfiguration.getDefaultStorageEngineId(), options);
        assertThat(variantAnnotator, is(instanceOf(CellBaseRestVariantAnnotator.class)));

        storageConfiguration.getStorageEngine().getVariant().getOptions().remove(VariantAnnotationManager.ANNOTATOR);
        options = new ObjectMap();
        variantAnnotator = VariantAnnotatorFactory.buildVariantAnnotator(storageConfiguration, storageConfiguration.getDefaultStorageEngineId(), options);
        assertThat(variantAnnotator, is(instanceOf(CellBaseRestVariantAnnotator.class)));

        options = new ObjectMap(VariantAnnotationManager.ANNOTATOR, VariantAnnotatorFactory.AnnotationSource.OTHER.toString())
                .append(VariantAnnotationManager.VARIANT_ANNOTATOR_CLASSNAME, TestCellBaseRestVariantAnnotator.class.getName());
        variantAnnotator = VariantAnnotatorFactory.buildVariantAnnotator(storageConfiguration, storageConfiguration.getDefaultStorageEngineId(), options);
        assertThat(variantAnnotator, is(instanceOf(TestCellBaseRestVariantAnnotator.class)));
    }


    @Test
    public void testSkipVariant() throws VariantAnnotatorException {
        ObjectMap options = new ObjectMap(VariantAnnotationManager.ANNOTATOR, VariantAnnotatorFactory.AnnotationSource.OTHER.toString())
                .append(VariantAnnotationManager.VARIANT_ANNOTATOR_CLASSNAME, TestCellBaseRestVariantAnnotator.class.getName());
        VariantAnnotator variantAnnotator = VariantAnnotatorFactory.buildVariantAnnotator(storageConfiguration, storageConfiguration.getDefaultStorageEngineId(), options);
        assertThat(variantAnnotator, is(instanceOf(TestCellBaseRestVariantAnnotator.class)));

        TestCellBaseRestVariantAnnotator testAnnotator = (TestCellBaseRestVariantAnnotator) variantAnnotator;
        testAnnotator.skip("10:1000:A:C");
        List<VariantAnnotation> annotate = testAnnotator.annotate(Arrays.asList(new Variant("10:999:A:C"), new Variant("10:1000:A:C"), new Variant("10:1001:A:C")));
        assertEquals(2, annotate.size());

    }

    @Test
    public void testErrorVariant() throws VariantAnnotatorException {
        ObjectMap options = new ObjectMap(VariantAnnotationManager.ANNOTATOR, VariantAnnotatorFactory.AnnotationSource.OTHER.toString())
                .append(VariantAnnotationManager.VARIANT_ANNOTATOR_CLASSNAME, TestCellBaseRestVariantAnnotator.class.getName());
        VariantAnnotator variantAnnotator = VariantAnnotatorFactory.buildVariantAnnotator(storageConfiguration, storageConfiguration.getDefaultStorageEngineId(), options);
        assertThat(variantAnnotator, is(instanceOf(TestCellBaseRestVariantAnnotator.class)));

        TestCellBaseRestVariantAnnotator testAnnotator = (TestCellBaseRestVariantAnnotator) variantAnnotator;
        testAnnotator.remove("10:1000:A:C");
        Exception exception = AbstractCellBaseVariantAnnotator.unexpectedVariantOrderException("10:1000:A:C", "10:1001:A:C");
        thrown.expect(exception.getClass());
        thrown.expectMessage(exception.getMessage());
        testAnnotator.annotate(Arrays.asList(new Variant("10:999:A:C"), new Variant("10:1000:A:C"), new Variant("10:1001:A:C")));
    }

    public static class TestCellBaseRestVariantAnnotator extends CellBaseRestVariantAnnotator {

        private final Set<String> skipvariants;
        private final Set<String> removevariants;

        public TestCellBaseRestVariantAnnotator(StorageConfiguration storageConfiguration, ObjectMap options) throws VariantAnnotatorException {
            super(storageConfiguration, options);
            skipvariants = new HashSet<>();
            removevariants = new HashSet<>();
            System.out.println("Create " + getClass());
        }

        public TestCellBaseRestVariantAnnotator skip(String variant) {
            skipvariants.add(variant);
            return this;
        }

        public TestCellBaseRestVariantAnnotator remove(String variant) {
            removevariants.add(variant);
            return this;
        }

        @Override
        protected List<QueryResult<VariantAnnotation>> annotateFiltered(List<Variant> variants) throws VariantAnnotatorException {
            List<QueryResult<VariantAnnotation>> queryResults = super.annotateFiltered(variants);
            for (Iterator<QueryResult<VariantAnnotation>> iterator = queryResults.iterator(); iterator.hasNext(); ) {
                QueryResult<VariantAnnotation> queryResult = iterator.next();
                assertEquals(1, queryResult.getResult().size());
                if (skipvariants.contains(queryResult.getId())) {
                    queryResult.setResult(Collections.emptyList());
                    queryResult.setNumResults(0);
                } else if (removevariants.contains(queryResult.getId())) {
                    iterator.remove();
                }
            }
            return queryResults;
        }
    }


}