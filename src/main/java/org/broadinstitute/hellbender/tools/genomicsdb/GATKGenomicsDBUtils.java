package org.broadinstitute.hellbender.tools.genomicsdb;

import com.googlecode.protobuf.format.JsonFormat;
import htsjdk.samtools.util.FileExtensions;
import org.broadinstitute.hellbender.exceptions.UserException;
import org.broadinstitute.hellbender.tools.walkers.annotator.AnnotationUtils;
import org.broadinstitute.hellbender.utils.Utils;
import org.broadinstitute.hellbender.utils.gcs.BucketUtils;
import org.broadinstitute.hellbender.utils.io.IOUtils;
import org.broadinstitute.hellbender.utils.variant.GATKVCFConstants;
import org.genomicsdb.importer.GenomicsDBImporter;
import org.genomicsdb.model.GenomicsDBExportConfiguration;
import org.genomicsdb.model.GenomicsDBVidMapProto;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.genomicsdb.GenomicsDBUtils.isGenomicsDBArray;
import static org.genomicsdb.GenomicsDBUtils.readEntireFile;

/**
 * Utility class containing various methods for working with GenomicsDB
 * Contains code to modify the GenomicsDB import input using the Protobuf API
 *
 * References:
 * GenomicsDB Protobuf structs: https://github.com/GenomicsDB/GenomicsDB/blob/master/src/resources/genomicsdb_vid_mapping.proto
 * Protobuf generated Java code guide:
 * https://developers.google.com/protocol-buffers/docs/javatutorial#the-protocol-buffer-api
 * https://developers.google.com/protocol-buffers/docs/reference/java-generated
 */
public class GATKGenomicsDBUtils {

    private static final String SUM = "sum";
    private static final String ELEMENT_WISE_SUM = "element_wise_sum";
    private static final String ELEMENT_WISE_FLOAT_SUM = "element_wise_float_sum";
    private static final String ELEMENT_WISE_INT_SUM = "element_wise_int_sum";
    private static final String HISTOGRAM_SUM = "histogram_sum";
    private static final String MOVE_TO_FORMAT = "move_to_FORMAT";

    private static final String GDB_TYPE_FLOAT = "float";
    private static final String GDB_TYPE_INT = "int";


    /**
     * Info and Allele-specific fields that need to be treated differently
     * will have to be explicitly overridden in this method during import.
     * Note that the recommendation is to perform this operation during the import phase
     * as only a limited set of mappings can be changed during export.
     *
     * @param importer that imports bcf/vcf streams into GenomicsDB
     */
    public static void updateImportProtobufVidMapping(GenomicsDBImporter importer) {
        //Get the in-memory Protobuf structure representing the vid information.
        GenomicsDBVidMapProto.VidMappingPB vidMapPB = importer.getProtobufVidMapping();
        if (vidMapPB == null) {
            throw new UserException("Could not get protobuf vid mappping object from GenomicsDBImporter");
        }

        // In vidMapPB, fields is a list of GenomicsDBVidMapProto.GenomicsDBFieldInfo objects.
        // Each GenomicsDBFieldInfo object contains information about a specific field in the
        // GenomicsDB store and this list is iterated to create a field name to list index map.
        final HashMap<String, Integer> fieldNameToIndexInVidFieldsList =
                getFieldNameToListIndexInProtobufVidMappingObject(vidMapPB);

        vidMapPB = updateINFOFieldCombineOperation(vidMapPB, fieldNameToIndexInVidFieldsList,
                GATKVCFConstants.RAW_MAPPING_QUALITY_WITH_DEPTH_KEY, ELEMENT_WISE_SUM);

        vidMapPB = updateAlleleSpecificINFOFieldCombineOperation(vidMapPB, fieldNameToIndexInVidFieldsList,
                GATKVCFConstants.AS_RAW_RMS_MAPPING_QUALITY_KEY, ELEMENT_WISE_FLOAT_SUM);

        //Update combine operations for GnarlyGenotyper
        //Note that this MQ format is deprecated, but was used by the prototype version of ReblockGVCF
        vidMapPB = updateINFOFieldCombineOperation(vidMapPB, fieldNameToIndexInVidFieldsList,
                GATKVCFConstants.MAPPING_QUALITY_DEPTH_DEPRECATED, SUM);
        vidMapPB = updateINFOFieldCombineOperation(vidMapPB, fieldNameToIndexInVidFieldsList,
                GATKVCFConstants.RAW_QUAL_APPROX_KEY, SUM);
        vidMapPB = updateINFOFieldCombineOperation(vidMapPB, fieldNameToIndexInVidFieldsList,
                GATKVCFConstants.VARIANT_DEPTH_KEY, SUM);
        vidMapPB = updateINFOFieldCombineOperation(vidMapPB, fieldNameToIndexInVidFieldsList,
                GATKVCFConstants.RAW_GENOTYPE_COUNT_KEY, ELEMENT_WISE_SUM);
        vidMapPB = updateAlleleSpecificINFOFieldCombineOperation(vidMapPB, fieldNameToIndexInVidFieldsList,
                GATKVCFConstants.AS_RAW_QUAL_APPROX_KEY, ELEMENT_WISE_INT_SUM);
        vidMapPB = updateFieldSetDisableRemapMissingAlleleToNonRef(vidMapPB, fieldNameToIndexInVidFieldsList,
                GATKVCFConstants.AS_RAW_RMS_MAPPING_QUALITY_KEY, true);
        vidMapPB = updateFieldSetDisableRemapMissingAlleleToNonRef(vidMapPB, fieldNameToIndexInVidFieldsList,
                GATKVCFConstants.AS_SB_TABLE_KEY, true);
        vidMapPB = updateINFOFieldCombineOperation(vidMapPB, fieldNameToIndexInVidFieldsList,
                GATKVCFConstants.TREE_SCORE, SUM);
        vidMapPB = updateINFOFieldCombineOperation(vidMapPB, fieldNameToIndexInVidFieldsList,
                GATKVCFConstants.HAPLOTYPE_COMPLEXITY_KEY, ELEMENT_WISE_SUM);
        vidMapPB = updateINFOFieldCombineOperation(vidMapPB, fieldNameToIndexInVidFieldsList,
                GATKVCFConstants.HAPLOTYPE_DOMINANCE_KEY, ELEMENT_WISE_SUM);
        vidMapPB = updateINFOFieldCombineOperation(vidMapPB, fieldNameToIndexInVidFieldsList,
                GATKVCFConstants.HAPLOTYPES_BEFORE_FILTERING_KEY, SUM);
        vidMapPB = updateINFOFieldCombineOperation(vidMapPB, fieldNameToIndexInVidFieldsList,
                GATKVCFConstants.HAPLOTYPES_FILTERED_KEY, SUM);



        importer.updateProtobufVidMapping(vidMapPB);
    }

    /**
     *
     * @param workspace path to the GenomicsDB workspace
     * @param callsetJson path to the GenomicsDB callset JSON
     * @param vidmapJson path to the GenomicsDB vidmap JSON
     * @param vcfHeader VCF with header information to use for header lines on export
     * @param genomicsDBOptions genotyping parameters to read from a GenomicsDB
     * @return a configuration to determine the output format when the GenomicsDB is queried
     */
    public static GenomicsDBExportConfiguration.ExportConfiguration createExportConfiguration(final String workspace,
                                                                                              final String callsetJson, final String vidmapJson,
                                                                                              final String vcfHeader, final GenomicsDBOptions genomicsDBOptions) {
        if (genomicsDBOptions.useGcsHdfsConnector()) {
          org.genomicsdb.GenomicsDBUtils.useGcsHdfsConnector(true);
        }
        final GenomicsDBExportConfiguration.ExportConfiguration.Builder exportConfigurationBuilder =
                GenomicsDBExportConfiguration.ExportConfiguration.newBuilder()
                        .setWorkspace(workspace)
                        .setReferenceGenome(genomicsDBOptions.getReference().toAbsolutePath().toString())
                        .setVidMappingFile(vidmapJson)
                        .setCallsetMappingFile(callsetJson)
                        .setVcfHeaderFilename(vcfHeader)
                        .setProduceGTField(genomicsDBOptions.doCallGenotypes())
                        .setProduceGTWithMinPLValueForSpanningDeletions(false)
                        .setSitesOnlyQuery(false)
                        .setMaxDiploidAltAllelesThatCanBeGenotyped(genomicsDBOptions.getMaxDiploidAltAllelesThatCanBeGenotyped())
                        .setMaxGenotypeCount(genomicsDBOptions.getMaxGenotypeCount())
                        .setEnableSharedPosixfsOptimizations(genomicsDBOptions.sharedPosixFSOptimizations());

        // For the multi-interval support, we create multiple arrays (directories) in a single workspace -
        // one per interval. So, if you wish to import intervals ("chr1", [ 1, 100M ]) and ("chr2", [ 1, 100M ]),
        // you end up with 2 directories named chr1$1$100M and chr2$1$100M. So, the array names depend on the
        // partition bounds.

        // During the read phase, the user only supplies the workspace. The array names are obtained by scanning
        // the entries in the workspace and reading the right arrays. For example, if you wish to read ("chr2",
        // 50, 50M), then only the second array is queried.

        // In the previous version of the tool, the array name was a constant - genomicsdb_array. The new version
        // will be backward compatible with respect to reads. Hence, if a directory named genomicsdb_array is found,
        // the array name is passed to the GenomicsDBFeatureReader otherwise the array names are generated from the
        // directory entries.
        if (isGenomicsDBArray(workspace, GenomicsDBConstants.DEFAULT_ARRAY_NAME)) {
            exportConfigurationBuilder.setArrayName(GenomicsDBConstants.DEFAULT_ARRAY_NAME);
        } else {
            exportConfigurationBuilder.setGenerateArrayNameFromPartitionBounds(true);
        }

        return exportConfigurationBuilder.build();
    }

    public static GenomicsDBExportConfiguration.ExportConfiguration createExportConfiguration(final String workspace,
                                                                                              final String callsetJson, final String vidmapJson,
                                                                                              final String vcfHeader) {
        return createExportConfiguration(workspace, callsetJson, vidmapJson, vcfHeader, null);
    }

    /**
     * Parse the vid json and create an in-memory Protobuf structure representing the
     * information in the JSON file
     *
     * @param vidmapJson vid JSON file
     * @return Protobuf object
     */
    public static GenomicsDBVidMapProto.VidMappingPB getProtobufVidMappingFromJsonFile(final String vidmapJson)
            throws IOException {
        final GenomicsDBVidMapProto.VidMappingPB.Builder vidMapBuilder = GenomicsDBVidMapProto.VidMappingPB.newBuilder();
        JsonFormat.merge(readEntireFile(vidmapJson), vidMapBuilder);
        return vidMapBuilder.build();
    }

    /**
     * In vidMapPB, fields is a list of GenomicsDBVidMapProto.GenomicsDBFieldInfo objects
     * Each GenomicsDBFieldInfo object contains information about a specific field in the GenomicsDB store
     * We iterate over the list and create a field name to list index map
     *
     * @param vidMapPB Protobuf vid mapping object
     * @return map from field name to index in vidMapPB.fields list
     */
    public static HashMap<String, Integer> getFieldNameToListIndexInProtobufVidMappingObject(
            final GenomicsDBVidMapProto.VidMappingPB vidMapPB) {
        final HashMap<String, Integer> fieldNameToIndexInVidFieldsList = new HashMap<>();
        for (int fieldIdx = 0; fieldIdx < vidMapPB.getFieldsCount(); ++fieldIdx) {
            fieldNameToIndexInVidFieldsList.put(vidMapPB.getFields(fieldIdx).getName(), fieldIdx);
        }
        return fieldNameToIndexInVidFieldsList;
    }

    /**
     * Update vid Protobuf object with new combine operation for field
     *
     * @param vidMapPB                        input vid object
     * @param fieldNameToIndexInVidFieldsList name to index in list
     * @param fieldName                       INFO field name
     * @param newCombineOperation             combine op ("sum", "median")
     * @return updated vid Protobuf object if field exists, else return original vidmap object
     */
    public static GenomicsDBVidMapProto.VidMappingPB updateINFOFieldCombineOperation(
            final GenomicsDBVidMapProto.VidMappingPB vidMapPB,
            final Map<String, Integer> fieldNameToIndexInVidFieldsList,
            final String fieldName,
            final String newCombineOperation) {
        final int fieldIdx = fieldNameToIndexInVidFieldsList.containsKey(fieldName)
                ? fieldNameToIndexInVidFieldsList.get(fieldName) : -1;
        if (fieldIdx >= 0) {
            //Would need to rebuild vidMapPB - so get top level builder first
            final GenomicsDBVidMapProto.VidMappingPB.Builder updatedVidMapBuilder = vidMapPB.toBuilder();
            //To update the list element corresponding to fieldName, we get the builder for that specific list element
            final GenomicsDBVidMapProto.GenomicsDBFieldInfo.Builder fieldBuilder =
                    updatedVidMapBuilder.getFieldsBuilder(fieldIdx);
            //And update its combine operation
            fieldBuilder.setVCFFieldCombineOperation(newCombineOperation);

            //Rebuild full vidMap
            return updatedVidMapBuilder.build();
        }
        return vidMapPB;
    }

    /**
     * Update vid Protobuf object with a new variable length descriptor, as for allele-specific annotations
     * @param vidMapPB input vid object
     * @param fieldNameToIndexInVidFieldsList name to index in list
     * @param fieldName INFO field name
     * @param newCombineOperation combine op ("histogram_sum", "element_wise_float_sum", "strand_bias_table")
     * @return updated vid Protobuf object if field exists, else null
     */
    public static GenomicsDBVidMapProto.VidMappingPB updateAlleleSpecificINFOFieldCombineOperation(
            final GenomicsDBVidMapProto.VidMappingPB vidMapPB,
            final Map<String, Integer> fieldNameToIndexInVidFieldsList,
            final String fieldName,
            final String newCombineOperation)
    {
        int fieldIdx = fieldNameToIndexInVidFieldsList.containsKey(fieldName)
                ? fieldNameToIndexInVidFieldsList.get(fieldName) : -1;
        if(fieldIdx >= 0) {
            //Would need to rebuild vidMapPB - so get top level builder first
            GenomicsDBVidMapProto.VidMappingPB.Builder updatedVidMapBuilder = vidMapPB.toBuilder();
            //To update the list element corresponding to fieldName, we get the builder for that specific list element
            GenomicsDBVidMapProto.GenomicsDBFieldInfo.Builder infoBuilder =
                    updatedVidMapBuilder.getFieldsBuilder(fieldIdx);

            GenomicsDBVidMapProto.FieldLengthDescriptorComponentPB.Builder lengthDescriptorComponentBuilder =
                    GenomicsDBVidMapProto.FieldLengthDescriptorComponentPB.newBuilder();

            infoBuilder.clearLength();
            infoBuilder.clearVcfDelimiter();
            infoBuilder.clearType();

            lengthDescriptorComponentBuilder.setVariableLengthDescriptor("R");
            infoBuilder.addLength(lengthDescriptorComponentBuilder.build());
            lengthDescriptorComponentBuilder.setVariableLengthDescriptor("var"); //ignored - can set anything here
            infoBuilder.addLength(lengthDescriptorComponentBuilder.build());
            infoBuilder.addVcfDelimiter(AnnotationUtils.ALLELE_SPECIFIC_RAW_DELIM);
            infoBuilder.addVcfDelimiter(AnnotationUtils.ALLELE_SPECIFIC_REDUCED_DELIM);

            if (newCombineOperation.equals(HISTOGRAM_SUM)) {
                //Each element of the vector is a tuple <float, int>
                infoBuilder.addType(GDB_TYPE_FLOAT);
                infoBuilder.addType(GDB_TYPE_INT);
                infoBuilder.setVCFFieldCombineOperation(HISTOGRAM_SUM);
            } else {
                infoBuilder.setVCFFieldCombineOperation(ELEMENT_WISE_SUM);
                if (newCombineOperation.equals(ELEMENT_WISE_FLOAT_SUM)) {
                    infoBuilder.addType(GDB_TYPE_FLOAT);
                } else {
                    infoBuilder.addType(GDB_TYPE_INT);
                }
            }

            //Rebuild full vidMap
            return updatedVidMapBuilder.build();
        }
        return vidMapPB;
    }

    /**
     * Update vid Protobuf field to set whether missing allele should be remapped with value from NON_REF
     * @param vidMapPB input vid object
     * @param fieldNameToIndexInVidFieldsList name to index in list
     * @param fieldName INFO field name
     * @param value boolean value - true to disable remapping missing value with value from NON_REF
     * @return updated vid Protobuf object if field exists, else return original protobuf object
     */
    public static GenomicsDBVidMapProto.VidMappingPB updateFieldSetDisableRemapMissingAlleleToNonRef(
            final GenomicsDBVidMapProto.VidMappingPB vidMapPB,
            final Map<String, Integer> fieldNameToIndexInVidFieldsList,
            final String fieldName,
            final boolean value)
    {
        int fieldIdx = fieldNameToIndexInVidFieldsList.containsKey(fieldName)
                ? fieldNameToIndexInVidFieldsList.get(fieldName) : -1;
        if(fieldIdx >= 0) {
            //Would need to rebuild vidMapPB - so get top level builder first
            GenomicsDBVidMapProto.VidMappingPB.Builder updatedVidMapBuilder = vidMapPB.toBuilder();
            //To update the list element corresponding to fieldName, we get the builder for that specific list element
            GenomicsDBVidMapProto.GenomicsDBFieldInfo.Builder infoBuilder =
                    updatedVidMapBuilder.getFieldsBuilder(fieldIdx);

            infoBuilder.setDisableRemapMissingWithNonRef(value);
            //Rebuild full vidMap
            return updatedVidMapBuilder.build();
        }
        return vidMapPB;
    }

    /*
     * Changes relative local file paths to be absolute file paths. Cloud Datastore paths are left unchanged
     * @param path to the resource
     * @return an absolute file path if the original path was a relative file path, otherwise the original path
     */
    public static String genomicsDBGetAbsolutePath(String path) {
        Utils.nonNull(path);
        if (path.contains("://")) {
            return path;
        } else {
            return BucketUtils.makeFilePathAbsolute(path);
        }
    }

    /**
     * Appends path to the given parent path. The parent path could be a URI or a File.
     * @param parentPath the folder to append the path
     * @param path the path relative to dir
     * @return the appended path as String
     */
    public static String genomicsDBApppendPaths(String parentPath, String path) {
        if (parentPath != null && parentPath.contains("://")) {
            if (parentPath.endsWith("/")) {
                return parentPath + path;
            } else {
                return parentPath + "/" + path;
            }
        } else {
            return IOUtils.appendPathToDir(parentPath, path);
        }
    }

    public static void assertVariantFileIsCompressedAndIndexed(final Path vcfPath) {
        assertVariantFileIsCompressedAndIndexed(vcfPath, null);
    }

    public static void assertVariantFileIsCompressedAndIndexed(final Path vcfPath, final Path optionalVCFindexPath) {
        if (!vcfPath.toString().toLowerCase().endsWith(FileExtensions.COMPRESSED_VCF)) {
            throw new UserException("Input variant files must be block compressed vcfs when using " +
                    GenomicsDBImport.BYPASS_FEATURE_READER + ", but " + vcfPath.toString() + " does not end with " +
                    "the standard file extension " + FileExtensions.COMPRESSED_VCF);
        }

        Path indexPath = optionalVCFindexPath != null ?
                optionalVCFindexPath :
                vcfPath.resolveSibling(vcfPath.getFileName() + FileExtensions.COMPRESSED_VCF_INDEX);
        IOUtils.assertFileIsReadable(indexPath);
    }
}
