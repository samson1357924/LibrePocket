import { runLabelManagerTests } from './label_manager.test';
import { runScannerSendCpaTests } from './scanner_sendcpa.test';
import { runCommentAuthTests, runCommentRunnerTests } from './comment_runner.test';

runLabelManagerTests()
  .then(() => runScannerSendCpaTests())
  .then(() => runCommentRunnerTests())
  .then(() => runCommentAuthTests())
  .catch((error: unknown) => {
    console.error(error);
    process.exitCode = 1;
  });
