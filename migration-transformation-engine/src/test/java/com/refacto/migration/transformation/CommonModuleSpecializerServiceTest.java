package com.refacto.migration.transformation;

import com.refacto.migration.core.model.CommonModuleSlice;
import com.refacto.migration.core.model.ModuleReorganizationItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CommonModuleSpecializerServiceTest {

    private final CommonModuleSpecializerService service = new CommonModuleSpecializerService();

    @Test
    void shouldDetectCommonModule(@TempDir Path tempDir) throws IOException {
        assertThat(service.isCommonModule(tempDir, "legacy-common")).isTrue();
        assertThat(service.isCommonModule(tempDir, "shared-utils")).isTrue();
        assertThat(service.isCommonModule(tempDir, "app-commons")).isTrue();
        assertThat(service.isCommonModule(tempDir, "payment-service")).isFalse();
    }

    @Test
    void shouldSpecializeCommonModuleAndPruneUnreferencedClasses(@TempDir Path tempDir) throws IOException {
        // 1. Module commun avec 2 classes : SharedCustomerDAO (utilisé) et StringUtilsHelper (non utilisé)
        Path commonDir = tempDir.resolve("legacy-common");
        Path commonSrc = commonDir.resolve("src/main/java/com/sample/common");
        Files.createDirectories(commonSrc);

        Files.writeString(commonDir.resolve("pom.xml"), """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.sample</groupId>
                    <artifactId>legacy-common</artifactId>
                    <version>1.0.0</version>
                </project>
                """);

        Files.writeString(commonSrc.resolve("SharedCustomerDAO.java"), """
                package com.sample.common;
                public class SharedCustomerDAO {
                    public void query() {}
                }
                """);

        Files.writeString(commonSrc.resolve("StringUtilsHelper.java"), """
                package com.sample.common;
                public class StringUtilsHelper {
                    public static boolean isBlank(String s) { return s == null || s.isEmpty(); }
                }
                """);

        // 2. Module enfant batch-payment qui référence uniquement SharedCustomerDAO
        Path batchDir = tempDir.resolve("batch-payment");
        Path batchSrc = batchDir.resolve("src/main/java/com/sample/batch");
        Files.createDirectories(batchSrc);

        Files.writeString(batchSrc.resolve("PaymentProcessor.java"), """
                package com.sample.batch;
                import com.sample.common.SharedCustomerDAO;
                public class PaymentProcessor {
                    private SharedCustomerDAO dao;
                }
                """);

        List<ModuleReorganizationItem> childModules = List.of(
                new ModuleReorganizationItem("batch-payment", "batch-payment", "batch-app/batch-payment", true)
        );

        // 3. Exécuter la spécialisation
        Map<String, CommonModuleSpecializerService.CommonClassInfo> classMap =
                service.scanCommonModuleClasses(tempDir, "legacy-common");
        assertThat(classMap).hasSize(2);

        CommonModuleSlice slice = service.specializeForSubProject(
                tempDir,
                "legacy-common",
                "batch-app",
                childModules,
                classMap
        );

        // 4. Vérifications du partitionnement
        assertThat(slice.included()).isTrue();
        assertThat(slice.targetRelativePath()).isEqualTo("batch-app/legacy-common");

        // SharedCustomerDAO doit être conservé
        assertThat(slice.retainedClasses())
                .anyMatch(c -> c.contains("SharedCustomerDAO.java"));

        // StringUtilsHelper doit être élagué (pruned)
        assertThat(slice.prunedClasses())
                .anyMatch(c -> c.contains("StringUtilsHelper.java"));

        // 5. Application physique et vérification sur disque
        service.applySlicePhysically(tempDir, "batch-app", slice, "");

        Path targetSliceDir = tempDir.resolve("batch-app/legacy-common");
        assertThat(targetSliceDir).exists().isDirectory();
        assertThat(targetSliceDir.resolve("pom.xml")).exists();

        // SharedCustomerDAO doit exister physiquement dans le sous-projet
        assertThat(targetSliceDir.resolve("src/main/java/com/sample/common/SharedCustomerDAO.java")).exists();

        // StringUtilsHelper doit avoir été supprimé de la copie du sous-projet
        assertThat(targetSliceDir.resolve("src/main/java/com/sample/common/StringUtilsHelper.java")).doesNotExist();

        // Le module d'origine legacy-common n'a pas été altéré
        assertThat(commonSrc.resolve("StringUtilsHelper.java")).exists();
    }
}
