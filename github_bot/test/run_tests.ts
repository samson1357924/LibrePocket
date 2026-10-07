import { runLabelManagerTests } from './label_manager.test';

runLabelManagerTests().catch((error: unknown) => {
  console.error(error);
  process.exitCode = 1;
});
