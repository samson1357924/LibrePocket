import { runLabelManagerTests } from './label_manager.test';
import { runScannerSendCpaTests } from './scanner_sendcpa.test';

runLabelManagerTests()
  .then(() => runScannerSendCpaTests())
  .catch((error: unknown) => {
    console.error(error);
    process.exitCode = 1;
  });
