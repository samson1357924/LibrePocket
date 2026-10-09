import { runLabelManagerTests } from './label_manager.test';
import { runScannerSendOpenAITests } from './scanner_sendopenai.test';
import { runCommentAuthTests, runCommentRunnerTests, runRouteEventTests } from './comment_runner.test';
import { runExecutionMatrixTests } from './execution_matrix.test';
import { runMergeBaseTests } from './merge_base.test';

runLabelManagerTests()
  .then(() => runScannerSendOpenAITests())
  .then(() => runCommentRunnerTests())
  .then(() => runCommentAuthTests())
  .then(() => runRouteEventTests())
  .then(() => runExecutionMatrixTests())
  .then(() => runMergeBaseTests())
  .catch((error: unknown) => {
    console.error(error);
    process.exitCode = 1;
  });
