import { runLabelManagerTests } from './label_manager.test';
import { runScannerSendOpenAITests } from './scanner_sendopenai.test';
import { runCommentAuthTests, runCommentRunnerTests } from './comment_runner.test';

runLabelManagerTests()
  .then(() => runScannerSendOpenAITests())
  .then(() => runCommentRunnerTests())
  .then(() => runCommentAuthTests())
  .catch((error: unknown) => {
    console.error(error);
    process.exitCode = 1;
  });
