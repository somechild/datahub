import React, { useEffect, useState } from 'react';
import Cookies from 'js-cookie';
import { message } from 'antd';
import { BrowserRouter as Router } from 'react-router-dom';
import { ApolloClient, ApolloProvider, createHttpLink, InMemoryCache, ServerError } from '@apollo/client';
import { onError } from '@apollo/client/link/error';
import { ThemeProvider } from 'styled-components';
import { Helmet, HelmetProvider } from 'react-helmet-async';
import './App.less';
import { Routes } from './app/Routes';
import { Theme } from './conf/theme/types';
import defaultThemeConfig from './conf/theme/theme_light.config.json';
import { PageRoutes } from './conf/Global';
import { isLoggedInVar } from './app/auth/checkAuthStatus';
import { GlobalCfg } from './conf';
import possibleTypesResult from './possibleTypes.generated';
import { ErrorCodes } from './app/shared/constants';

/*
    Construct Apollo Client
*/
const httpLink = createHttpLink({ uri: '/api/v2/graphql' });

const errorLink = onError((error) => {
    const { networkError, graphQLErrors } = error;
    if (networkError) {
        const serverError = networkError as ServerError;
        if (serverError.statusCode === ErrorCodes.Unauthorized) {
            isLoggedInVar(false);
            Cookies.remove(GlobalCfg.CLIENT_AUTH_COOKIE);
            const currentPath = window.location.pathname + window.location.search;
            window.location.replace(`${PageRoutes.AUTHENTICATE}?redirect_uri=${encodeURIComponent(currentPath)}`);
        }
    }
    if (graphQLErrors && graphQLErrors.length) {
        const firstError = graphQLErrors[0];
        const { extensions } = firstError;
        const errorCode = extensions && (extensions.code as number);
        // Fallback in case the calling component does not handle.
        message.error(`${firstError.message} (code ${errorCode})`, 3);
    }
});

const client = new ApolloClient({
    connectToDevTools: true,
    link: errorLink.concat(httpLink),
    cache: new InMemoryCache({
        typePolicies: {
            Query: {
                fields: {
                    dataset: {
                        merge: (oldObj, newObj) => {
                            return { ...oldObj, ...newObj };
                        },
                    },
                },
            },
        },
        // need to define possibleTypes to allow us to use Apollo cache with union types
        possibleTypes: possibleTypesResult.possibleTypes,
    }),
    credentials: 'include',
    defaultOptions: {
        watchQuery: {
            fetchPolicy: 'no-cache',
        },
        query: {
            fetchPolicy: 'no-cache',
        },
    },
});

export const InnerApp: React.VFC = () => {
    const [dynamicThemeConfig, setDynamicThemeConfig] = useState<Theme>(defaultThemeConfig);

    useEffect(() => {
        if (import.meta.env.DEV) {
            import(/* @vite-ignore */ `./conf/theme/${import.meta.env.REACT_APP_THEME_CONFIG}`).then((theme) => {
                setDynamicThemeConfig(theme);
            });
        } else {
            // Send a request to the server to get the theme config.
            fetch(`/assets/conf/theme/${import.meta.env.REACT_APP_THEME_CONFIG}`)
                .then((response) => response.json())
                .then((theme) => {
                    setDynamicThemeConfig(theme);
                });
        }
    }, []);

    return (
        <HelmetProvider>
            <Helmet>
                <title>{dynamicThemeConfig.content.title}</title>
            </Helmet>
            <ThemeProvider theme={dynamicThemeConfig}>
                <Router>
                    <Routes />
                </Router>
            </ThemeProvider>
        </HelmetProvider>
    );
};

export const App: React.VFC = () => {
    return (
        <ApolloProvider client={client}>
            <InnerApp />
        </ApolloProvider>
    );
};
