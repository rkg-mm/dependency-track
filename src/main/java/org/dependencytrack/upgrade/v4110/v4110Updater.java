/*
 * This file is part of Dependency-Track.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (c) OWASP Foundation. All Rights Reserved.
 */
package org.dependencytrack.upgrade.v4110;

import alpine.common.logging.Logger;
import alpine.persistence.AlpineQueryManager;
import alpine.server.upgrade.AbstractUpgradeItem;
import alpine.server.util.DbUtil;
import org.dependencytrack.model.Severity;
import org.dependencytrack.util.VulnerabilityUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

public class v4110Updater extends AbstractUpgradeItem {

    private static final Logger LOGGER = Logger.getLogger(v4110Updater.class);

    @Override
    public String getSchemaVersion() {
        return "4.11.0";
    }

    @Override
    public void executeUpgrade(final AlpineQueryManager qm, final Connection connection) throws Exception {
        setProjectCollectionLogicDefault(connection);
        dropCweTable(connection);
        computeVulnerabilitySeverities(connection);
        extendPurlColumnLengths(connection);
    }

    private static void setProjectCollectionLogicDefault(final Connection connection) throws Exception {
        LOGGER.info("Setting collection logic of all projects to default NONE");
        try (final Statement stmt = connection.createStatement()) {
            stmt.execute("UPDATE \"PROJECT\" SET \"COLLECTION_LOGIC\" = 'NONE'");
        }
    }

    private static void dropCweTable(final Connection connection) throws Exception {
        final boolean shouldReEnableAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);

        try {
            // In DT v4.4.x and older, the VULNERABILITY table had a foreign key constraint
            // on the CWE table. Drop the constraint, the index on the CWE column, as well as
            // the column itself if they still exist.
            // Unfortunately, the FK constraint has a generic name, as it was generated by DataNucleus.
            try (final Statement stmt = connection.createStatement()) {
                LOGGER.info("Dropping foreign key constraint from \"VULNERABILITY\".\"CWE\"");
                stmt.executeUpdate("""
                        ALTER TABLE "VULNERABILITY" DROP CONSTRAINT IF EXISTS "VULNERABILITY_FK1"
                        """);

                if (DbUtil.isH2()) {
                    LOGGER.info("Dropping index \"VULNERABILITY_CWE_IDX\"");
                    stmt.executeUpdate("""
                        DROP INDEX IF EXISTS "VULNERABILITY_CWE_IDX"
                        """);
                } else {
                    LOGGER.info("Dropping index \"VULNERABILITY\".\"VULNERABILITY_CWE_IDX\"");
                    stmt.executeUpdate("""
                        DROP INDEX IF EXISTS "VULNERABILITY"."VULNERABILITY_CWE_IDX"
                        """);
                }

                LOGGER.info("Dropping column \"VULNERABILITY\".\"CWE\"");
                stmt.executeUpdate("""
                        ALTER TABLE "VULNERABILITY" DROP COLUMN IF EXISTS "CWE"
                        """);

                LOGGER.info("Dropping table \"CWE\"");
                stmt.executeUpdate("""
                        DROP TABLE IF EXISTS "CWE"
                        """);
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }

            connection.commit();
        } finally {
            if (shouldReEnableAutoCommit) {
                connection.setAutoCommit(true);
            }
        }
    }

    private static void computeVulnerabilitySeverities(final Connection connection) throws Exception {
        // Part of a fix for https://github.com/DependencyTrack/dependency-track/issues/2474.
        // Recomputes all database severity values with value NULL of a vulnerability and updates them in the database.
        LOGGER.info("Computing severities for vulnerabilities where severity is currently NULL");
        try (final PreparedStatement selectStatement = connection.prepareStatement("""
                 SELECT
                  "CVSSV2BASESCORE",
                  "CVSSV3BASESCORE",
                  "OWASPRRLIKELIHOODSCORE",
                  "OWASPRRTECHNICALIMPACTSCORE",
                  "OWASPRRBUSINESSIMPACTSCORE",
                  "VULNID"
                FROM
                  "VULNERABILITY"
                WHERE
                  "SEVERITY" is NULL
                """);
             final PreparedStatement updateStatement = connection.prepareStatement("""
                     UPDATE "VULNERABILITY" SET "SEVERITY" = ? WHERE "VULNID" = ?
                     """)) {
            int batchSize = 0, numBatches = 0, numUpdates = 0;
            final ResultSet rs = selectStatement.executeQuery();
            while (rs.next()) {
                final String vulnID = rs.getString(6);
                final Severity severity = VulnerabilityUtil.getSeverity(
                        rs.getBigDecimal(1),
                        rs.getBigDecimal(2),
                        rs.getBigDecimal(3),
                        rs.getBigDecimal(4),
                        rs.getBigDecimal(5)
                );

                updateStatement.setString(1, severity.name());
                updateStatement.setString(2, vulnID);
                updateStatement.addBatch();
                if (++batchSize == 500) {
                    updateStatement.executeBatch();
                    numUpdates += batchSize;
                    numBatches++;
                    batchSize = 0;
                }
            }

            if (batchSize > 0) {
                updateStatement.executeBatch();
                numUpdates += batchSize;
                numBatches++;
            }

            LOGGER.info("Updated %d vulnerabilities in %d batches".formatted(numUpdates, numBatches));
        }
    }

    private static void extendPurlColumnLengths(final Connection connection) throws Exception {
        LOGGER.info("Extending length of PURL and PURLCOORDINATES columns from 255 to 786");
        if (DbUtil.isH2() || DbUtil.isPostgreSQL()) {
            try (final Statement statement = connection.createStatement()) {
                statement.addBatch("""
                        ALTER TABLE "COMPONENT" ALTER COLUMN "PURL" SET DATA TYPE VARCHAR(786)""");
                statement.addBatch("""
                        ALTER TABLE "COMPONENT" ALTER COLUMN "PURLCOORDINATES" SET DATA TYPE VARCHAR(786)""");
                statement.addBatch("""
                        ALTER TABLE "COMPONENTANALYSISCACHE" ALTER COLUMN "TARGET" SET DATA TYPE VARCHAR(786)""");
                statement.addBatch("""
                        ALTER TABLE "PROJECT" ALTER COLUMN "PURL" SET DATA TYPE VARCHAR(786)""");
                statement.executeBatch();
            }
        } else if (DbUtil.isMssql()) {
            try (final Statement statement = connection.createStatement()) {
                statement.addBatch("""
                        ALTER TABLE "COMPONENT" ALTER COLUMN "PURL" VARCHAR(786) NULL""");
                statement.addBatch("""
                        ALTER TABLE "COMPONENT" ALTER COLUMN "PURLCOORDINATES" VARCHAR(786) NULL""");
                statement.addBatch("""
                        ALTER TABLE "COMPONENTANALYSISCACHE" ALTER COLUMN "TARGET" VARCHAR(786) NOT NULL""");
                statement.addBatch("""
                        ALTER TABLE "PROJECT" ALTER COLUMN "PURL" VARCHAR(786) NULL""");
                statement.executeBatch();
            }
        } else if (DbUtil.isMysql()) {
            try (final Statement statement = connection.createStatement()) {
                statement.addBatch("""
                        ALTER TABLE "COMPONENT" MODIFY COLUMN "PURL" VARCHAR(786)""");
                statement.addBatch("""
                        ALTER TABLE "COMPONENT" MODIFY COLUMN "PURLCOORDINATES" VARCHAR(786)""");
                statement.addBatch("""
                        ALTER TABLE "COMPONENTANALYSISCACHE" MODIFY COLUMN "TARGET" VARCHAR(786)""");
                statement.addBatch("""
                        ALTER TABLE "PROJECT" MODIFY COLUMN "PURL" VARCHAR(786)""");
                statement.executeBatch();
            }
        } else {
            throw new IllegalStateException("Unrecognized database type");
        }
    }

}
