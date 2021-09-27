/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.delta;

import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.ConnectorSplit;
import com.facebook.presto.spi.ConnectorSplitSource;
import com.facebook.presto.spi.ConnectorTableLayoutHandle;
import com.facebook.presto.spi.connector.ConnectorPartitionHandle;
import com.facebook.presto.spi.connector.ConnectorSplitManager;
import com.facebook.presto.spi.connector.ConnectorTransactionHandle;
import com.google.common.collect.ImmutableList;
import io.delta.standalone.actions.AddFile;
import io.delta.standalone.data.CloseableIterator;
import org.apache.hadoop.fs.Path;

import javax.inject.Inject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.concurrent.CompletableFuture;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedFuture;

public class DeltaSplitManager
        implements ConnectorSplitManager
{
    private final String connectorId;
    private final DeltaConfig deltaConfig;
    private final DeltaClient deltaClient;

    @Inject
    public DeltaSplitManager(DeltaConnectorId connectorId, DeltaConfig deltaConfig, DeltaClient deltaClient)
    {
        this.connectorId = requireNonNull(connectorId, "connectorId is null").toString();
        this.deltaConfig = requireNonNull(deltaConfig, "deltaConfig is null");
        this.deltaClient = requireNonNull(deltaClient, "deltaClient is null");
    }

    @Override
    public ConnectorSplitSource getSplits(
            ConnectorTransactionHandle handle,
            ConnectorSession session,
            ConnectorTableLayoutHandle layout,
            SplitSchedulingContext splitSchedulingContext)
    {
        return new DeltaSplitSource(session, ((DeltaTableLayoutHandle) layout).getTable().getDeltaTable());
    }

    private class DeltaSplitSource
            implements ConnectorSplitSource
    {
        private final DeltaTable deltaTable;
        private final CloseableIterator<AddFile> fileIterator;
        private final int maxBatchSize;

        DeltaSplitSource(ConnectorSession session, DeltaTable deltaTable)
        {
            this.deltaTable = deltaTable;
            this.fileIterator = deltaClient.listFiles(session, deltaTable);
            this.maxBatchSize = deltaConfig.getMaxSplitsBatchSize();
        }

        @Override
        public CompletableFuture<ConnectorSplitBatch> getNextBatch(ConnectorPartitionHandle partitionHandle, int maxSize)
        {
            ImmutableList.Builder<ConnectorSplit> splitBuilder = ImmutableList.builder();
            long currentSplitCount = 0;
            while (fileIterator.hasNext() && currentSplitCount < maxSize && currentSplitCount < maxBatchSize) {
                AddFile file = fileIterator.next();
                Path filePath = new Path(deltaTable.getTableLocation(), URI.create(file.getPath()).getPath());
                splitBuilder.add(new DeltaSplit(
                        connectorId,
                        deltaTable.getSchemaName(),
                        deltaTable.getTableName(),
                        filePath.toString(),
                        0, /* start */
                        file.getSize() /* split length - default is read the entire file in one split */,
                        file.getSize()));
                currentSplitCount++;
            }

            return completedFuture(new ConnectorSplitBatch(splitBuilder.build(), !fileIterator.hasNext()));
        }

        @Override
        public void close()
        {
            try {
                fileIterator.close();
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public boolean isFinished()
        {
            return !fileIterator.hasNext();
        }
    }
}