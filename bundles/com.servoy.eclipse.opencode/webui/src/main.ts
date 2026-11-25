import { bootstrapApplication } from '@angular/platform-browser';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { ApplicationConfig, provideZonelessChangeDetection } from '@angular/core';

import { AppComponent } from './app/app.component';
import { ThemeService } from './app/services/theme.service';
import { installNativeUiGuard } from './app/services/native-ui-guard';

// Apply the theme before the app renders so there is no light-to-dark flash.
new ThemeService().applyResolved();
installNativeUiGuard();

const appConfig: ApplicationConfig = {
  providers: [provideZonelessChangeDetection(), provideHttpClient(withFetch())]
};

bootstrapApplication(AppComponent, appConfig).catch((err) => console.error(err));
