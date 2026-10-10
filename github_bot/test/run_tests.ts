import { runLabelManagerTests } from './label_manager.test';
import { runScannerSendOpenAITests } from './scanner_sendopenai.test';
import { runCommentAuthTests, runCommentRunnerTests, runRouteEventTests } from './comment_runner.test';
import { runExecutionMatrixTests } from './execution_matrix.test';
import { runReviewCountLedgerTests } from './review_count_ledger.test';
import { runStickyLabelTests } from './sticky_labels.test';
import { runMergeBaseTests } from './merge_base.test';
import { runStage4P2Tests } from './stage4_p2.test';
import { runPhase2HTests } from './phase2_h.test';
import { runPrChunksTests } from './pr_chunks.test';

runLabelManagerTests()
  .then(() => runScannerSendOpenAITests())
  .then(() => runCommentRunnerTests())
  .then(() => runCommentAuthTests())
  .then(() => runRouteEventTests())
  .then(() => runExecutionMatrixTests())
  .then(() => runReviewCountLedgerTests())
  .then(() => runStickyLabelTests())
  .then(() => runMergeBaseTests())
  .then(() => runStage4P2Tests())
  .then(() => runPhase2HTests())
  .then(() => runPrChunksTests())
  .catch((error: unknown) => {
    console.error(error);
    process.exitCode = 1;
  });
